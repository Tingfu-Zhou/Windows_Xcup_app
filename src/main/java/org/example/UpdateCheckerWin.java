package org.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows 版本更新检查器：负责拉取远端 JSON，比较版本并提示用户。
 */
public final class UpdateCheckerWin {

    private static final Logger LOGGER = Logger.getLogger(UpdateCheckerWin.class.getName());
    private static final String VERSION_URL = "https://cdn.xswy.tech/app/version.json";
    private static final String PLATFORM_KEY = "windows";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;

    private UpdateCheckerWin() {
    }

    /**
     * 启动异步检测：仅负责发起请求与弹窗提醒。
     *
     * @param owner 弹窗归属的 Stage，可为空
     */
    public static void checkForUpdates(Stage owner) {
        final String currentVersion = System.getProperty("app.version", "0.0.0").trim();
        CompletableFuture.runAsync(() -> fetchAndHandle(currentVersion, owner));
    }

    private static void fetchAndHandle(String currentVersion, Stage owner) {
        try {
            URL url = URI.create(VERSION_URL).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Cache-Control", "no-cache");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return;
            }

            StringBuilder payload = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    payload.append(line);
                }
            }

            JsonObject root = JsonParser.parseString(payload.toString()).getAsJsonObject();
            if (!root.has(PLATFORM_KEY)) {
                return;
            }

            JsonObject windows = root.getAsJsonObject(PLATFORM_KEY);
            String latest = firstNonNull(
                    getStringOrNull(windows, "latest"),
                    getStringOrNull(windows, "latestVersionName"));
            String minSupported = firstNonNull(
                    getStringOrNull(windows, "minSupported"),
                    getStringOrNull(windows, "minsupported"));
            String landingUrl = getStringOrNull(windows, "landingUrl");
            String notes = getStringOrNull(windows, "notes");

            if (latest == null || minSupported == null || landingUrl == null) {
                return;
            }

            int cmpMin = compareVersions(currentVersion, minSupported);
            int cmpLatest = compareVersions(currentVersion, latest);
            boolean forceUpdate = cmpMin < 0;
            boolean optionalUpdate = !forceUpdate && cmpLatest < 0;

            if (!forceUpdate && !optionalUpdate) {
                return;
            }

            Platform.runLater(() -> {
                if (forceUpdate) {
                    showForceDialog(owner, landingUrl, currentVersion, latest, minSupported, notes);
                } else {
                    showOptionalDialog(owner, landingUrl, currentVersion, latest, notes);
                }
            });
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "版本检查失败: {0}", e.getMessage());
        }
    }

    private static void showForceDialog(Stage owner,
                                        String landingUrl,
                                        String currentVersion,
                                        String latest,
                                        String minSupported,
                                        String notes) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("发现重要更新");
        alert.setHeaderText("当前版本过低，必须升级后才能继续使用");
        alert.setContentText(buildForceContent(currentVersion, latest, minSupported, notes));

        ButtonType download = new ButtonType("前往下载", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(download);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent()) {
            openLandingUrl(landingUrl);
            Platform.exit();
            System.exit(0);
        } else {
            // 用户尝试关闭窗口 → 直接退出程序，避免继续使用旧版本
            Platform.exit();
            System.exit(0);
        }
    }

    private static void showOptionalDialog(Stage owner,
                                           String landingUrl,
                                           String currentVersion,
                                           String latest,
                                           String notes) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("发现新版本");
        alert.setHeaderText("建议升级到最新版本");
        alert.setContentText(buildOptionalContent(currentVersion, latest, notes));

        ButtonType download = new ButtonType("前往下载", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("稍后再说", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(download, later);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == download) {
            openLandingUrl(landingUrl);
        }
    }

    private static void openLandingUrl(String landingUrl) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(landingUrl));
            } else {
                LOGGER.warning("当前系统不支持 Desktop.browse，URL: " + landingUrl);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "打开更新页面失败: {0}", e.getMessage());
        }
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int maxLength = Math.max(leftParts.length, rightParts.length);

        for (int i = 0; i < maxLength; i++) {
            int l = i < leftParts.length ? parsePart(leftParts[i]) : 0;
            int r = i < rightParts.length ? parsePart(rightParts[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String getStringOrNull(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString().trim();
    }

    private static String firstNonNull(String primary, String fallback) {
        return primary != null && !primary.isEmpty() ? primary : fallback;
    }

    private static String buildForceContent(String currentVersion,
                                            String latest,
                                            String minSupported,
                                            String notes) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前版本：").append(currentVersion)
                .append("\n最低支持版本：").append(minSupported)
                .append("\n最新版本：").append(latest)
                .append("\n\n请立即前往下载页面更新，否则将退出程序。");
        if (notes != null && !notes.isEmpty()) {
            builder.append("\n\n更新说明：").append(notes);
        }
        return builder.toString();
    }

    private static String buildOptionalContent(String currentVersion,
                                               String latest,
                                               String notes) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前版本：").append(currentVersion)
                .append("\n最新版本：").append(latest)
                .append("\n\n是否立即前往下载页面更新？");
        if (notes != null && !notes.isEmpty()) {
            builder.append("\n\n更新说明：").append(notes);
        }
        return builder.toString();
    }
}

