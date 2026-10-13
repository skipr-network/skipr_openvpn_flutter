package id.laskarmedia.openvpn_flutter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import de.blinkt.openvpn.OnVPNStatusChangeListener;
import de.blinkt.openvpn.VPNHelper;
import de.blinkt.openvpn.core.OpenVPNService;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

/**
 * OpenvpnFlutterPlugin
 *
 * TT-2334: VPN permission dialogs must not share a MethodChannel.Result with
 * other vpncontrol calls (e.g. getVpnSettingsStatus on resume). A stolen Result
 * leaves request_permission / connect Futures hanging and parks Flutter CRS
 * {@code _inFlight} forever after Cancel.
 */
public class OpenVPNFlutterPlugin implements FlutterPlugin, ActivityAware, PluginRegistry.ActivityResultListener {

    private MethodChannel vpnControlMethod;
    private EventChannel vpnStageEvent;
    private EventChannel.EventSink vpnStageSink;

    private static final String EVENT_CHANNEL_VPN_STAGE = "id.laskarmedia.openvpn_flutter/vpnstage";
    private static final String METHOD_CHANNEL_VPN_CONTROL = "id.laskarmedia.openvpn_flutter/vpncontrol";
    private static final int VPN_PERMISSION_REQUEST_CODE = 24;

    private static String config = "", username = "", password = "", name = "";

    private static ArrayList<String> bypassPackages;
    private static Boolean isNonGoogleDevice;
    @SuppressLint("StaticFieldLeak")
    private static VPNHelper vpnHelper;
    private Activity activity;
    private ActivityPluginBinding activityBinding;

    Context mContext;

    /**
     * Only the in-flight VPN permission activity owns this Result.
     * Sync methods must use the call's local {@code result} parameter.
     */
    @Nullable
    private static MethodChannel.Result pendingPermissionResult;

    /** True when {@code connect} launched the permission dialog (start VPN on grant). */
    private static boolean startVpnAfterPermission;

    /**
     * @deprecated Do not store every MethodChannel.Result globally. Kept as a
     * no-op for binary compatibility; permission Results are set only when a
     * VPN permission activity is started.
     */
    @Deprecated
    public static void setResult(MethodChannel.Result methodChannelResult) {
        // Intentionally unused — see pendingPermissionResult.
    }

    /**
     * Completes a pending VPN permission request (MainActivity dual-path and
     * {@link #onActivityResult}). Idempotent after the first completion.
     */
    public static void connectWhileGranted(boolean granted) {
        completePermissionRequest(granted);
    }

    private static void completePermissionRequest(boolean granted) {
        final MethodChannel.Result pending = pendingPermissionResult;
        pendingPermissionResult = null;
        final boolean shouldStartVpn = startVpnAfterPermission;
        startVpnAfterPermission = false;

        if (pending != null) {
            pending.success(granted);
        }

        if (granted && shouldStartVpn && vpnHelper != null && config != null && !config.isEmpty()) {
            vpnHelper.startVPN(config, username, password, name, bypassPackages, isNonGoogleDevice);
        }
    }

    private static void armPermissionRequest(
            @NonNull MethodChannel.Result result,
            boolean startVpnOnGrant
    ) {
        pendingPermissionResult = result;
        startVpnAfterPermission = startVpnOnGrant;
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        vpnStageEvent = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL_VPN_STAGE);
        vpnControlMethod = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL_VPN_CONTROL);

        vpnStageEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                vpnStageSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                if (vpnStageSink != null) vpnStageSink.endOfStream();
            }
        });

        vpnControlMethod.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "getVpnSettingsStatus":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine needs to be initialized", "");
                        return;
                    }
                    vpnHelper.setOnVPNSettingsStatusTempListener((isAlwaysOn, isLockdownEnabled) -> {
                        final Map<String, Object> vpnStatus = new HashMap<>();
                        vpnStatus.put("isAlwaysOn", isAlwaysOn);
                        vpnStatus.put("isLockdownEnabled", isLockdownEnabled);
                        result.success(vpnStatus);
                    });
                    vpnHelper.getVpnSettingsStatus();
                    break;
                case "status":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    result.success(vpnHelper.status.toString());
                    break;
                case "initialize":
                    vpnHelper = new VPNHelper(activity);
                    vpnHelper.setOnVPNStatusChangeListener(new OnVPNStatusChangeListener() {
                        @Override
                        public void onVPNStatusChanged(String status) {
                            updateStage(status);
                        }

                        @Override
                        public void onConnectionStatusChanged(String duration, String lastPacketReceive, String byteIn, String byteOut) {

                        }
                    });
                    result.success(updateVPNStages());
                    break;
                case "disconnect":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    vpnHelper.stopVPN();
                    updateStage("disconnected");
                    result.success(null);
                    break;
                case "connect":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }

                    config = call.argument("config");
                    name = call.argument("name");
                    username = call.argument("username");
                    password = call.argument("password");
                    bypassPackages = call.argument("bypass_packages");
                    isNonGoogleDevice = call.argument("is_non_google_device");

                    if (config == null) {
                        result.error("-2", "OpenVPN Config is required", "");
                        return;
                    }

                    final Intent permission = VpnService.prepare(activity);
                    if (permission != null) {
                        armPermissionRequest(result, true);
                        activity.startActivityForResult(permission, VPN_PERMISSION_REQUEST_CODE);
                        return;
                    }
                    vpnHelper.startVPN(config, username, password, name, bypassPackages, isNonGoogleDevice);
                    // TT-2306 / TT-2334: always complete the channel Future when
                    // permission is already granted (previously left hanging).
                    result.success(true);
                    break;
                case "stage":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    result.success(updateVPNStages());
                    break;
                case "request_permission":
                    final Intent request = VpnService.prepare(activity);
                    if (request != null) {
                        armPermissionRequest(result, false);
                        activity.startActivityForResult(request, VPN_PERMISSION_REQUEST_CODE);
                        return;
                    }
                    result.success(true);
                    break;
                case "check_permission":
                    final Intent checkPermission = VpnService.prepare(activity);
                    if (checkPermission != null) {
                        result.success(false);
                        return;
                    }
                    result.success(true);
                    break;
                default:
                    result.notImplemented();
            }
        });
        mContext = binding.getApplicationContext();
    }

    public void updateStage(String stage) {
        if (stage == null) stage = "idle";
        if (vpnStageSink != null) vpnStageSink.success(stage.toLowerCase());
    }


    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        vpnStageEvent.setStreamHandler(null);
        vpnControlMethod.setMethodCallHandler(null);
    }


    private String updateVPNStages() {
        if (OpenVPNService.getStatus() == null) {
            OpenVPNService.setDefaultStatus();
        }
        updateStage(OpenVPNService.getStatus());
        return OpenVPNService.getStatus();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        activityBinding = binding;
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(this);
            activityBinding = null;
        }
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        activityBinding = binding;
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(this);
            activityBinding = null;
        }
        activity = null;
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != VPN_PERMISSION_REQUEST_CODE) {
            return false;
        }
        if (pendingPermissionResult == null) {
            // MainActivity may already have completed via connectWhileGranted.
            return false;
        }
        completePermissionRequest(resultCode == Activity.RESULT_OK);
        return true;
    }
}
