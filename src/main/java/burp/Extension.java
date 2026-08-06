package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import com.google.gson.Gson;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class Extension implements BurpExtension {
    private MontoyaApi api;
    private Gson gson;
    private Settings settings;
    private SettingsTab settingsTab;

    private static final String HEADER_KEY = "Awesometlsconfig";

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        this.gson = new Gson();
        this.settings = new Settings(api);
        this.settingsTab = new SettingsTab(settings);

        api.extension().setName("Awesome TLS");
        api.extension().registerUnloadingHandler(() -> {
            var err = ServerLibrary.INSTANCE.StopServer();
            if (!err.isEmpty()) {
                api.logging().logToError(err);
            }
        });
        api.userInterface().registerSuiteTab("Awesome TLS", settingsTab.getUI());
        api.proxy().registerRequestHandler(new ProxyRequestHandler() {
            @Override
            public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest interceptedRequest) {
                return processHttpRequest(interceptedRequest);
            }

            @Override
            public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest interceptedRequest) {
                return ProxyRequestReceivedAction.continueWith(interceptedRequest);
            }
        });

        var listenAddress = settings.getSpoofProxyAddress();
        new Thread(() -> {
            // StartServer blocks for the lifetime of the server. A failure to bind returns within
            // milliseconds, so this optimistic status is corrected below before anyone can read it.
            settingsTab.setServerStatus("Running — listening on " + listenAddress, false);

            var err = ServerLibrary.INSTANCE.StartServer(listenAddress);

            // Reaching here means the server stopped, or never managed to start.
            if (!err.isEmpty()) {
                api.logging().logToError(err);
                settingsTab.setServerStatus("Stopped — " + err, true);

                var isGraceful = err.contains("Server stopped") || err.contains("address already in use");
                if (!isGraceful) {
                    api.extension().unload(); // fatal error; disable the extension
                }
            } else {
                settingsTab.setServerStatus("Stopped", true);
            }
        }).start();
    }

    private ProxyRequestToBeSentAction processHttpRequest(InterceptedRequest request) {
        try {
            var requestURL = new URI(request.url()).toURL();

            if (requestURL.getHost().equals("awesome-tls-error")) {
                throw new Error(new String(request.body().getBytes(), StandardCharsets.UTF_8));
            }

            var headerOrder = new String[request.headers().size()];
            for (var i = 0; i < request.headers().size(); i++) {
                headerOrder[i] = request.headers().get(i).name();
            }

            var transportConfig = settings.toTransportConfig(requestURL.getHost());
            transportConfig.Host = requestURL.getHost();
            transportConfig.Scheme = requestURL.getProtocol();
            transportConfig.HeaderOrder = headerOrder;

            var goConfigJSON = gson.toJson(transportConfig);
            var url = new URI("https://" + settings.getSpoofProxyAddress()).toURL();
            var httpService = HttpService.httpService(url.getHost(), url.getPort(), Objects.equals(url.getProtocol(), "https"));
            var nextRequest = request.withService(httpService).withAddedHeader(HEADER_KEY, goConfigJSON);

            return ProxyRequestToBeSentAction.continueWith(nextRequest);
        } catch (Exception e) {
            api.logging().logToError("Http request error: " + e);
            return null;
        }
    }
}
