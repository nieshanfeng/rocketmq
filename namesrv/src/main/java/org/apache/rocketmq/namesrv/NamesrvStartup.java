/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.srvutil.ShutdownHookThread;
import org.slf4j.LoggerFactory;

public class NamesrvStartup {

    private static InternalLogger log;
    private static Properties properties = null;
    private static CommandLine commandLine = null;

    public static void main(String[] args) {
        main0(args);
    }

    public static NamesrvController main0(String[] args) {

        try {
            //1.创建NamesrvController对象
            NamesrvController controller = createNamesrvController(args);
            //2.启动Namesrv服务端
            start(controller);
            //3.启动成功，输出序列化类型
            String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
            log.info(tip);
            System.out.printf("%s%n", tip);
            return controller;
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

        return null;
    }

    public static NamesrvController createNamesrvController(String[] args) throws IOException, JoranException {
        // 设置版本
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));
        //PackageConflictDetect.detectFastjson();

        // 命令行选项解析
        Options options = ServerUtil.buildCommandlineOptions(new Options());
        // 解析 `mqnamesrv` 命令行参数，用于启动namesrv的参数
        commandLine = ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options), new PosixParser());
        // 命令行无 `mqnamesrv` 参数，退出
        if (null == commandLine) {
            System.exit(-1);
            return null;
        }
        // Nameserver相关配置
        final NamesrvConfig namesrvConfig = new NamesrvConfig();
        // Netty服务器连接相关配置
        final NettyServerConfig nettyServerConfig = new NettyServerConfig();
        // 设置启动监听端口
        nettyServerConfig.setListenPort(9876);
        // 解析命令行参数 -c,从批定配置文件中填充NamesrvConfig,NettyServerConfig配置类
        if (commandLine.hasOption('c')) {
            String file = commandLine.getOptionValue('c');
            if (file != null) {
                // 加载资源文件
                InputStream in = new BufferedInputStream(new FileInputStream(file));
                properties = new Properties();
                // 将资源文件解析成Properties对象
                properties.load(in);
                // 填充namesrvConfig属性
                MixAll.properties2Object(properties, namesrvConfig);
                // 填充nettyServerConfig属性
                MixAll.properties2Object(properties, nettyServerConfig);
                // 设置MQ配置文件存储路径
                namesrvConfig.setConfigStorePath(file);

                System.out.printf("load config properties file OK, %s%n", file);
                // 关闭流
                in.close();
            }
        }
        // 如果启动命令中带了-p参数,打印NameServer的相关配置信息
        if (commandLine.hasOption('p')) {
            InternalLogger console = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_CONSOLE_NAME);
            MixAll.printObjectProperties(console, namesrvConfig);
            MixAll.printObjectProperties(console, nettyServerConfig);
            System.exit(0);
        }
        // 启动参数填充到namesrvConfig,nettyServerConfig的属性中
        MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);
        // RocketMQ主目录为空,打印异常信息,退出
        if (null == namesrvConfig.getRocketmqHome()) {
            System.out.printf("Please set the %s variable in your environment to match the location of the RocketMQ installation%n", MixAll.ROCKETMQ_HOME_ENV);
            System.exit(-2);
        }
        // 日志相关设置
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(lc);
        lc.reset();
        configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");

        log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

        MixAll.printObjectProperties(log, namesrvConfig);
        MixAll.printObjectProperties(log, nettyServerConfig);
        // 创建NamesrvController
        final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig);

        // remember all configs to prevent discard
        // 将当前解析启动参数的配置信息设置进全局配置configuration的allConfigs属性中，以防丢失
        controller.getConfiguration().registerConfig(properties);

        return controller;
    }

    public static NamesrvController start(final NamesrvController controller) throws Exception {

        if (null == controller) {
            throw new IllegalArgumentException("NamesrvController is null");
        }

        boolean initResult = controller.initialize();
        if (!initResult) {
            controller.shutdown();
            System.exit(-3);
        }

        Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(log, new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                controller.shutdown();
                return null;
            }
        }));

        controller.start();

        return controller;
    }

    public static void shutdown(final NamesrvController controller) {
        controller.shutdown();
    }

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Name server config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config items");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }

    public static Properties getProperties() {
        return properties;
    }
}
