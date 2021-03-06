package vproxyapp.app;

import vfd.VFDConfig;
import vmirror.Mirror;
import vproxy.fstack.FStackUtil;
import vproxyapp.app.args.*;
import vproxyapp.controller.StdIOController;
import vproxyapp.process.Shutdown;
import vproxyapp.vproxyx.Daemon;
import vproxyapp.vproxyx.Simple;
import vproxybase.Config;
import vproxybase.dns.Resolver;
import vproxybase.util.*;
import vproxyx.HelloWorld;
import vproxyx.KcpTun;
import vproxyx.WebSocksProxyAgent;
import vproxyx.WebSocksProxyServer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final String _HELP_STR_ = "" +
        "vproxy: usage java " + Main.class.getName() + " \\" +
        "\n\t\thelp                                         Show this message" +
        "\n" +
        "\n\t\tversion                                      Show version" +
        "\n" +
        "\n\t\tload ${filename}                             Load configuration from file" +
        "\n" +
        "\n\t\tcheck                                        check and exit" +
        "\n" +
        "\n\t\tresp-controller ${address} ${password}       Start the resp-controller, will" +
        "\n\t\t                                             be named as `resp-controller`" +
        "\n\t\thttp-controller ${address}                   Start the http-controller, will" +
        "\n\t\t                                             be named as `http-controller`" +
        "\n\t\tdocker-network-plugin-controller ${path}     Start the docker-network-plugin-" +
        "\n\t\t                                             controller, will be named as" +
        "\n\t\t                                             `docker-network-plugin-controller`" +
        "\n\t\tallowSystemCallInNonStdIOController          Allow system call in all controllers" +
        "\n" +
        "\n\t\tnoStdIOController                            StdIOController will not start" +
        "\n\t\t                                             if the flag is set" +
        "\n\t\tsigIntDirectlyShutdown                       Directly shutdown when got sig int" +
        "\n" +
        "\n\t\tpidFile                                      Set the pid file path" +
        "\n" +
        "\n\t\tnoLoadLast                                   Do not load last config on start up" +
        "\n" +
        "\n\t\tnoSave                                       Disable the ability to save config" +
        "\n" +
        "\n\t\tnoStartupBindCheck                           Disable bind check when loading config" +
        "\n\t\t                                             when launching. Will be automatically" +
        "\n\t\t                                             added when reloading using Systemd module" +
        "\n\t\tautoSaveFile ${filename}                     File path for auto saving" +
        "";
    private static boolean exitAfterLoading = false;

    private static void beforeStart() {
        OOMHandler.handleOOM();
        if (VFDConfig.useFStack) {
            try {
                FStackUtil.init();
            } catch (IOException e) {
                Logger.shouldNotHappen("initiate f-stack failed", e);
                Utils.exit(1);
            }
        }

        if (!Config.mirrorConfigPath.isBlank()) {
            try {
                Mirror.init(Config.mirrorConfigPath);
            } catch (Exception e) {
                Logger.fatal(LogType.INVALID_EXTERNAL_DATA, "initiate mirror failed", e);
                Utils.exit(1);
            }
        }

        Resolver.getDefault();
    }

    private static void runApp(String appClass, String[] args) {
        try {
            switch (appClass) {
                case "WebSocksProxyAgent":
                case "WebSocksAgent":
                case "wsagent":
                    WebSocksProxyAgent.main0(args);
                    break;
                case "WebSocksProxyServer":
                case "WebSocksServer":
                case "wsserver":
                    WebSocksProxyServer.main0(args);
                    break;
                case "KcpTun":
                case "kcptun":
                    KcpTun.main0(args);
                    break;
                case "Simple":
                case "simple":
                    Application.create();
                    Simple.main0(args);
                    break;
                case "Daemon":
                case "daemon":
                    Daemon.main0(args);
                    break;
                case "HelloWorld":
                case "helloworld":
                    HelloWorld.main0(args);
                    break;
                default:
                    System.err.println("unknown AppClass: " + appClass);
                    Utils.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Utils.exit(1);
        }
    }

    private static String[] checkFlagDeployInArguments(String[] args) {
        if (System.getProperty("eploy") != null) {
            // do not modify if -Deploy is already set
            return args;
        }
        List<String> returnArgs = new ArrayList<>(args.length);
        boolean found = false;
        for (final var arg : args) {
            if (arg.startsWith("-Deploy=")) {
                if (found) {
                    // should only appear once
                    throw new IllegalArgumentException("Cannot set multiple -Deploy= to run.");
                }
                found = true;
                System.setProperty("eploy", arg.substring("-Deploy=".length()));
            } else if (arg.startsWith("-D")) {
                // other properties can be set freely
                var kv = arg.substring("-D".length());
                if (kv.contains("=")) {
                    var k = kv.substring(0, kv.indexOf("=")).trim();
                    var v = kv.substring(kv.indexOf("=") + "=".length()).trim();
                    if (!k.isEmpty() && !v.isEmpty()) {
                        System.setProperty(k, v);
                        continue;
                    }
                }
                returnArgs.add(arg);
            } else {
                returnArgs.add(arg);
            }
        }
        //noinspection ToArrayCallWithZeroLengthArrayArgument
        return returnArgs.toArray(new String[returnArgs.size()]);
    }

    public static void main(String[] args) {
        args = checkFlagDeployInArguments(args);
        beforeStart();

        // check for system properties and may run an app
        // apps can be found in vproxyx package
        String appClass = Config.appClass;
        if (appClass != null) {
            runApp(appClass, args);
            if (VFDConfig.useFStack) {
                FStackUtil.run();
            }
            return;
        }

        try {
            Application.create();
        } catch (IOException e) {
            System.err.println("start application failed! " + e);
            e.printStackTrace();
            Utils.exit(1);
            return;
        }
        // init the address updater (should be called after Application initiates)
        ServerAddressUpdater.init();
        // start ControlEventLoop
        Application.get().controlEventLoop.loop();

        // every other thing should start after the loop

        // init ctx
        MainCtx ctx = new MainCtx();
        ctx.addOp(new AllowSystemCallInNonStdIOControllerOp());
        ctx.addOp(new CheckOp());
        ctx.addOp(new HttpControllerOp());
        ctx.addOp(new DockerNetworkPluginControllerOp());
        ctx.addOp(new LoadOp());
        ctx.addOp(new NoLoadLastOp());
        ctx.addOp(new NoSaveOp());
        ctx.addOp(new NoStartupBindCheckOp());
        ctx.addOp(new NoStdIOControllerOp());
        ctx.addOp(new PidFileOp());
        ctx.addOp(new RespControllerOp());
        ctx.addOp(new SigIntDirectlyShutdownOp());
        ctx.addOp(new AutoSaveFileOp());

        // load config if specified in args
        for (int i = 0; i < args.length; ++i) {
            String arg = args[i];
            String next = i + 1 < args.length ? args[i + 1] : null;
            String next2 = i + 2 < args.length ? args[i + 2] : null;
            switch (arg) {
                case "version":
                    System.out.println(Application.get().version);
                    Utils.exit(0);
                    return;
                case "help":
                    System.out.println(_HELP_STR_);
                    Utils.exit(0);
                    return;
                default:
                    var op = ctx.seekOp(arg);
                    if (op == null) {
                        System.err.println("unknown argument `" + arg + "`");
                        Utils.exit(1);
                        return;
                    }
                    var cnt = op.argCount();
                    assert cnt <= 2; // make it simple for now...
                    if (cnt == 0) {
                        ctx.addTodo(op, new String[0]);
                    } else {
                        // check next
                        if (next == null) {
                            System.err.println("`" + arg + "` expects more arguments");
                            Utils.exit(1);
                            return;
                        }
                        if (cnt == 1) {
                            ctx.addTodo(op, new String[]{next});
                            ++i;
                        } else {
                            assert cnt == 2;
                            if (next2 == null) {
                                System.err.println("`" + arg + "` expects more arguments");
                                Utils.exit(1);
                                return;
                            }
                            ctx.addTodo(op, new String[]{next, next2});
                            i += 2;
                        }
                    }
            }
        }
        ctx.executeAll();

        if (!ctx.get("loaded", false) && !Config.configLoadingDisabled) {
            File f = new File(Shutdown.defaultFilePath());
            if (f.exists()) {
                // load last config
                Logger.alert("trying to load from last saved config " + f.getAbsolutePath());
                Logger.alert("if the process fails to start, please manually remove " + f.getAbsolutePath() + " and start from scratch");
                if (ctx.get("isCheck", false)) {
                    exitAfterLoading = true;
                }
                JoinCallback<String, Throwable> cb = new JoinCallback<>(new CallbackInMain());
                try {
                    Shutdown.load(null, cb);
                } catch (Exception e) {
                    Logger.error(LogType.ALERT, "got exception when do pre-loading: " + Utils.formatErr(e));
                    Utils.exit(1);
                    return;
                }
                cb.join();
            }
        }
        // launch the default http controller
        if (!ctx.get("hasHttpController", false)) {
            String hostport = "127.0.0.1:18776";
            int ret = new HttpControllerOp().execute(ctx, new String[]{hostport});
            if (ret != 0) {
                Utils.exit(ret);
            }
            Logger.alert("default http-controller started on " + hostport);
        }
        // launch the default resp controller
        if (!ctx.get("hasRespController", false)) {
            String hostport = "127.0.0.1:16309";
            String pass = "123456";
            int ret = new RespControllerOp().execute(ctx, new String[]{hostport, pass});
            if (ret != 0) {
                Utils.exit(ret);
            }
            Logger.alert("default resp-controller started on " + hostport + " with password " + pass);
        }

        // write pid file
        if (!ctx.get("isCheck", false)) {
            try {
                Shutdown.writePid(ctx.get("pidFilePath", null));
            } catch (Exception e) {
                Logger.fatal(LogType.UNEXPECTED, "writing pid failed: " + Utils.formatErr(e));
                // failed on writing pid file is not a critical error
                // so we don't quit
            }
        }

        // exit if it's checking
        if (ctx.get("isCheck", false)) {
            if (!exitAfterLoading) {
                System.out.println("ok");
                Utils.exit(0);
                return;
            }
        }

        // start controllers

        if (!ctx.get("noStdIOController", false)) {
            // start stdioController
            StdIOController controller = new StdIOController();
            new Thread(controller::start, "StdIOControllerThread").start();
        }

        // run main app
        // init signal hooks
        Shutdown.initSignal();
        // start scheduled saving task
        Application.get().controlEventLoop.getSelectorEventLoop().period(60 * 60 * 1000, Main::saveConfig);

        if (VFDConfig.useFStack) {
            FStackUtil.run();
        }
    }

    private static void saveConfig() {
        try {
            Shutdown.autoSave();
        } catch (Exception e) {
            Logger.shouldNotHappen("failed to save config", e);
        }
    }

    public static class CallbackInMain extends Callback<String, Throwable> {
        @Override
        protected void onSucceeded(String value) {
            Config.checkBind = true;
            if (exitAfterLoading) {
                System.out.println("ok");
                Utils.exit(0);
            }
        }

        @Override
        protected void onFailed(Throwable err) {
            System.err.println(Utils.formatErr(err));
            Utils.exit(1);
        }
    }
}
