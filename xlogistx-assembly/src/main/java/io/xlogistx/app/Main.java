package io.xlogistx.app;


import io.xlogistx.http.NIOHTTPServer;
import io.xlogistx.http.NIOHTTPServerCreator;
import org.zoxweb.server.ds.mongo.sync.SyncMongoDS;
import org.zoxweb.server.io.IOUtil;
import org.zoxweb.server.logging.LogWrapper;
import org.zoxweb.server.net.NIOConfig;
import org.zoxweb.server.net.NIOSocket;
import org.zoxweb.server.net.security.IPBlockerListener;
import org.zoxweb.server.task.TaskUtil;
import org.zoxweb.server.util.GSONUtil;
import org.zoxweb.shared.api.APIConfigInfo;
import org.zoxweb.shared.api.APIConfigInfoDAO;
import org.zoxweb.shared.api.APIRegistrar;
import org.zoxweb.shared.data.ConfigDAO;
import org.zoxweb.shared.http.HTTPServerConfig;
import org.zoxweb.shared.security.IPBlockerConfig;
import org.zoxweb.shared.util.GetNameValue;
import org.zoxweb.shared.util.SetNameValue;
import org.zoxweb.shared.util.SharedStringUtil;
import org.zoxweb.shared.util.SharedUtil;

import java.io.File;
import java.util.List;


public class Main {
    public final static LogWrapper log = new LogWrapper(Main.class.getName()).setEnabled(false);

    public enum Param
            implements SetNameValue<Object> {
        WS(NIOHTTPServerCreator.RESOURCE_NAME),
        NI_CONFIG(NIOConfig.RESOURCE_NAME),
        IP_BLOCKER(IPBlockerListener.RESOURCE_NAME),
        DS_CONFIG("DataStore")
        ;
        private final String name;
        private Object val;

        Param(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setValue(Object value) {
            val = value;
        }

        @Override
        public Object getValue() {
            return val;
        }

        @Override
        public void setName(String name) {

        }
    }

    public static void main(String... args) {
        try {

            List<GetNameValue<String>> parameters = SharedStringUtil.parseStrings('=', args);
            NIOHTTPServer ws;
            NIOSocket nioSocket = null;
            IPBlockerListener ipBlocker;
            NIOHTTPServerCreator httpServerCreator = new NIOHTTPServerCreator();
            NIOConfig nioConfig = new NIOConfig();
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("-dbg"))
                    log.setEnabled(true);
            }

            for (GetNameValue<String> gnvs : parameters) {
                Param p = SharedUtil.lookupEnum(gnvs.getName(), Param.values());
                if (p != null) {
                    File file;
                    switch (p) {

                        case WS:
                            file = IOUtil.locateFile(gnvs.getValue());
                            HTTPServerConfig hsc = GSONUtil.fromJSON(IOUtil.inputStreamToString(file), HTTPServerConfig.class);
                            if (log.isEnabled()) log.getLogger().info("" + hsc);
                            if (log.isEnabled()) log.getLogger().info("" + hsc.getConnectionConfigs());
                            httpServerCreator.setAppConfig(hsc);
                            ws = httpServerCreator.createApp();
                            nioSocket = httpServerCreator.getNIOSocket();
                            p.setValue(ws);
                            break;
                        case NI_CONFIG:
                            file = IOUtil.locateFile(gnvs.getValue());
                            ConfigDAO configDAO = GSONUtil.fromJSON(IOUtil.inputStreamToString(file));
                            if (log.isEnabled()) log.getLogger().info(GSONUtil.toJSON(configDAO, true, false, false));
                            nioConfig.setAppConfig(configDAO).setNIOSocket(nioSocket);
                            nioSocket = nioConfig.createApp();
                            nioSocket.setEventManager(TaskUtil.defaultEventManager());
                            p.setValue(nioSocket);
                            break;
                        case IP_BLOCKER:
                            file = IOUtil.locateFile(gnvs.getValue());
                            IPBlockerConfig ipBlockerConfig = GSONUtil.fromJSON(IOUtil.inputStreamToString(file), IPBlockerConfig.class);
                            IPBlockerListener.Creator c = new IPBlockerListener.Creator();
                            c.setAppConfig(ipBlockerConfig);
                            ipBlocker = c.createApp();
                            p.setValue(ipBlocker);
                            break;
                        case DS_CONFIG:
                            file = IOUtil.locateFile(gnvs.getValue());
                            APIConfigInfo dataStoreConfig = GSONUtil.fromJSON(IOUtil.inputStreamToString(file), APIConfigInfoDAO.class);
                            SyncMongoDS dataStore = new SyncMongoDS();
                            dataStore.setAPIConfigInfo(dataStoreConfig);
                            APIRegistrar.SINGLETON.setDefault(dataStore);
                            break;
                    }
                }
            }

            Object lastApp = null;
            StringBuilder message = new StringBuilder();
            for (Param p : Param.values()) {
                if (p.getValue() != null) {
                    message.append(" " + p.getName());
                    lastApp = p.getValue();
                }


            }

            if (lastApp == null) {
                throw new IllegalArgumentException(parameters + " Invalid configuration");
            }

            log.getLogger().info("App Started:" + message);
            log.getLogger().info("We have one NIOSocket " + (nioConfig.getNIOSocket() == httpServerCreator.getNIOSocket()));

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("usage: [" + Param.WS.getName() + "=wsonfig.json] [" + Param.NI_CONFIG.getName() + "=niconfig.json] [" + Param.IP_BLOCKER.getName() + "=ipbconfig.json]" + " [" + Param.DS_CONFIG.getName() + "=dsconfig.json]");
            System.exit(-1);
        }
    }
}
