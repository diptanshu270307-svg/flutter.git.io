package com.finmesh.app;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.gson.Gson;

import java.util.List;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

/**
 * ══════════════════════════════════════════════════════════════
 * MainActivity.java  — Phase 4: Flutter ↔ Java MethodChannel Bridge
 * ══════════════════════════════════════════════════════════════
 *
 * This is the CENTRAL hub of the Android side. It:
 *   1. Sets up the Flutter embedding (extends FlutterActivity)
 *   2. Registers the MethodChannel with key "com.finmesh/native"
 *   3. Routes Flutter method calls to the correct Java handler
 *   4. Handles runtime permission requests for Nearby Connections
 *
 * ALL calls from Flutter land here first, then get routed to
 * WalletManager, BiometricHelper, or NearbyConnectionsManager.
 *
 * MethodChannel Contract (Flutter ↔ Java):
 * ┌─────────────────────┬──────────────────────────┬─────────────────────┐
 * │ Method Name         │ Arguments (from Flutter)  │ Return to Flutter   │
 * ├─────────────────────┼──────────────────────────┼─────────────────────┤
 * │ authenticateAndPay  │ amount(double),recipient  │ txId String         │
 * │ getBalance          │ none                      │ double (balance)    │
 * │ getTransactions     │ none                      │ JSON string         │
 * │ startMerchantMode   │ merchantName(String)      │ "OK" String         │
 * │ startPayerMode      │ none                      │ "OK" String         │
 * │ stopNearby          │ none                      │ "OK" String         │
 * │ addDemoFunds        │ amount(double)             │ new balance double  │
 * └─────────────────────┴──────────────────────────┴─────────────────────┘
 */
public class MainActivity extends FlutterActivity
        implements NearbyConnectionsManager.NearbyCallback {

    private static final String TAG     = "MainActivity";
    private static final String CHANNEL = "com.finmesh/native";  // Must match Dart side!
    private static final int    REQ_PERMISSIONS = 200;

    // ── Core Managers ────────────────────────────────────────────
    private WalletManager              walletManager;
    private BiometricHelper            biometricHelper;
    private NearbyConnectionsManager   nearbyManager;

    // ── MethodChannel result — saved for async callbacks ─────────
    // (BiometricPrompt and Nearby are async, so we save the result
    //  and call it later when the callback fires)
    private MethodChannel.Result pendingResult = null;
    private MethodChannel        methodChannel = null;

    // ── Gson for JSON serialization ──────────────────────────────
    private final Gson gson = new Gson();

    // ════════════════════════════════════════════════════════════
    //   FLUTTER ENGINE CONFIGURATION
    // ════════════════════════════════════════════════════════════

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        // ── Initialize all managers ───────────────────────────────
        walletManager   = WalletManager.getInstance(this);
        biometricHelper = new BiometricHelper(this);
        nearbyManager   = new NearbyConnectionsManager(this, this);

        // ── Register the MethodChannel ────────────────────────────
        methodChannel = new MethodChannel(
            flutterEngine.getDartExecutor().getBinaryMessenger(),
            CHANNEL
        );

        methodChannel.setMethodCallHandler((call, result) -> {
            Log.d(TAG, "Flutter called: " + call.method);

            switch (call.method) {

                // ──────────────────────────────────────────────────
                //  WALLET OPERATIONS
                // ──────────────────────────────────────────────────

                case "getBalance":
                    // Returns the current wallet balance to Flutter
                    result.success((double) walletManager.getBalance());
                    break;

                case "getTransactions":
                    // Returns full tx history as JSON string
                    String txJson = gson.toJson(walletManager.getTransactionHistory());
                    result.success(txJson);
                    break;

                case "addDemoFunds":
                    // Add mock funds (for demo/testing only)
                    double addAmount = call.argument("amount");
                    walletManager.credit((float) addAmount);
                    result.success((double) walletManager.getBalance());
                    break;

                case "setUserName":
                    String newName = call.argument("name");
                    walletManager.setUserName(newName);
                    result.success("OK");
                    break;

                case "getUserName":
                    result.success(walletManager.getUserName());
                    break;

                // ──────────────────────────────────────────────────
                //  BIOMETRIC + PAYMENT (ASYNC — result saved)
                // ──────────────────────────────────────────────────

                case "authenticateAndPay":
                    // Step 1: Get args
                    double payAmount  = call.argument("amount");
                    String recipient  = call.argument("recipient");

                    // Step 2: Check balance before even showing biometric
                    if (walletManager.getBalance() < (float) payAmount) {
                        result.error("INSUFFICIENT_FUNDS",
                            "Balance ₹" + walletManager.getBalance()
                            + " is less than ₹" + payAmount, null);
                        return;
                    }

                    // Step 3: Check if Nearby is connected
                    if (!nearbyManager.isConnected()) {
                        result.error("NOT_CONNECTED",
                            "No merchant device connected. Please scan first.", null);
                        return;
                    }

                    // Step 4: Save result for async callback + launch biometric
                    pendingResult = result;
                    launchBiometricForPayment((float) payAmount, recipient);
                    break;

                // ──────────────────────────────────────────────────
                //  NEARBY CONNECTIONS
                // ──────────────────────────────────────────────────

                case "startMerchantMode":
                    String merchantName = call.argument("merchantName");
                    requestPermissionsAndRun(() -> {
                        nearbyManager.startAdvertising(merchantName);
                        result.success("OK");
                    }, result);
                    break;

                case "startPayerMode":
                    requestPermissionsAndRun(() -> {
                        nearbyManager.startDiscovery();
                        result.success("OK");
                    }, result);
                    break;

                case "stopNearby":
                    nearbyManager.stopAll();
                    result.success("OK");
                    break;

                case "isConnected":
                    result.success(nearbyManager.isConnected());
                    break;

                // ──────────────────────────────────────────────────
                //  UNRECOGNIZED METHOD
                // ──────────────────────────────────────────────────

                default:
                    result.notImplemented();
                    break;
            }
        });
    }

    // ════════════════════════════════════════════════════════════
    //   BIOMETRIC PAYMENT FLOW (called from MethodChannel handler)
    // ════════════════════════════════════════════════════════════

    private void launchBiometricForPayment(float amount, String recipient) {
        if (!biometricHelper.isBiometricAvailable()) {
            // Device has no biometrics — skip auth and pay directly (hackathon fallback)
            Log.w(TAG, "No biometrics available. Proceeding without auth.");
            executePaymentAfterAuth(amount, recipient);
            return;
        }

        biometricHelper.authenticate(
            "Authorize Payment",
            "₹" + String.format("%.2f", amount) + " → " + recipient,
            new BiometricHelper.BiometricCallback() {

                @Override
                public void onSuccess() {
                    // ✅ Fingerprint accepted — now execute the payment
                    executePaymentAfterAuth(amount, recipient);
                }

                @Override
                public void onFailure() {
                    // Wrong fingerprint — dialog stays open (Android handles retry)
                    // We don't need to do anything here
                    Log.w(TAG, "Biometric: wrong fingerprint scanned");
                }

                @Override
                public void onError(int errorCode, String errorMsg) {
                    // User cancelled or hardware error — return error to Flutter
                    if (pendingResult != null) {
                        pendingResult.error("BIOMETRIC_ERROR", errorMsg, errorCode);
                        pendingResult = null;
                    }
                }
            }
        );
    }

    private void executePaymentAfterAuth(float amount, String recipient) {
        // Debit from local wallet
        boolean success = walletManager.debit(amount);
        if (!success) {
            if (pendingResult != null) {
                pendingResult.error("INSUFFICIENT_FUNDS", "Balance too low after auth check", null);
                pendingResult = null;
            }
            return;
        }

        // Send payment over Nearby Connections
        nearbyManager.sendPayment(
            nearbyManager.getConnectedEndpointId(),
            amount,
            walletManager.getUserName()
        );
        // Note: pendingResult.success() will be called inside onPaymentSent()
    }

    // ════════════════════════════════════════════════════════════
    //   NEARBY CALLBACK IMPLEMENTATIONS
    // ════════════════════════════════════════════════════════════

    @Override
    public void onAdvertisingStarted() {
        runOnUiThread(() ->
            notifyFlutter("nearbyEvent", "ADVERTISING_STARTED")
        );
    }

    @Override
    public void onDiscoveryStarted() {
        runOnUiThread(() ->
            notifyFlutter("nearbyEvent", "DISCOVERY_STARTED")
        );
    }

    @Override
    public void onConnected(String endpointId) {
        runOnUiThread(() ->
            notifyFlutter("nearbyEvent", "CONNECTED:" + endpointId)
        );
    }

    @Override
    public void onDisconnected(String endpointId) {
        runOnUiThread(() ->
            notifyFlutter("nearbyEvent", "DISCONNECTED:" + endpointId)
        );
    }

    @Override
    public void onMerchantFound(String endpointId, String merchantName) {
        runOnUiThread(() ->
            notifyFlutter("nearbyEvent", "MERCHANT_FOUND:" + merchantName + ":" + endpointId)
        );
    }

    @Override
    public void onMerchantLost(String endpointId) {
        runOnUiThread(() ->
            notifyFlutter("nearbyEvent", "MERCHANT_LOST:" + endpointId)
        );
    }

    @Override
    public void onPaymentSent(String txId, float amount) {
        // Record the transaction locally
        walletManager.recordTransaction(txId, amount, "SENT",
            nearbyManager.getConnectedEndpointId() != null
                ? nearbyManager.getConnectedEndpointId() : "Unknown");

        runOnUiThread(() -> {
            // Return the transaction ID to Flutter
            if (pendingResult != null) {
                pendingResult.success(txId);
                pendingResult = null;
            }
            // Also push an event to Flutter's event stream
            notifyFlutter("paymentEvent", "SENT:" + txId + ":AMOUNT:" + amount);
        });
    }

    @Override
    public void onPaymentReceived(String endpointId, String txId,
                                  String senderName, float amount, long timestamp) {
        // Credit the wallet
        walletManager.credit(amount);
        walletManager.recordTransaction(txId, amount, "RECEIVED", senderName);

        runOnUiThread(() -> {
            // Push event to Flutter so UI can update
            notifyFlutter("paymentEvent",
                "RECEIVED:" + txId + ":FROM:" + senderName + ":AMOUNT:" + amount);
            Toast.makeText(this,
                "💰 Received ₹" + String.format("%.2f", amount) + " from " + senderName,
                Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onMessageReceived(String endpointId, String message) {
        Log.d(TAG, "Message from [" + endpointId + "]: " + message);
        runOnUiThread(() ->
            notifyFlutter("nearbyEvent", "MESSAGE:" + message)
        );
    }

    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "Nearby error: " + errorMessage);
        runOnUiThread(() -> {
            if (pendingResult != null) {
                pendingResult.error("NEARBY_ERROR", errorMessage, null);
                pendingResult = null;
            }
            notifyFlutter("nearbyEvent", "ERROR:" + errorMessage);
        });
    }

    // ════════════════════════════════════════════════════════════
    //   PUSH EVENTS BACK TO FLUTTER (invokeMethod)
    // ════════════════════════════════════════════════════════════

    /**
     * Pushes a spontaneous event from Java → Flutter.
     * Flutter listens to these via its MethodCallHandler or an EventChannel.
     *
     * @param method  Event type (e.g., "nearbyEvent", "paymentEvent")
     * @param data    Event payload string
     */
    private void notifyFlutter(String method, String data) {
        if (methodChannel != null) {
            methodChannel.invokeMethod(method, data);
        }
    }

    // ════════════════════════════════════════════════════════════
    //   PERMISSIONS
    // ════════════════════════════════════════════════════════════

    private Runnable pendingAction = null;
    private MethodChannel.Result permissionResult = null;

    /**
     * Checks if all Nearby permissions are granted.
     * If yes: runs the action immediately.
     * If no: requests permissions, then runs the action in the callback.
     */
    private void requestPermissionsAndRun(Runnable action, MethodChannel.Result result) {
        List<String> missing = NearbyConnectionsManager.getMissingPermissions(this);

        if (missing.isEmpty()) {
            action.run();
        } else {
            pendingAction    = action;
            permissionResult = result;
            ActivityCompat.requestPermissions(
                this,
                missing.toArray(new String[0]),
                REQ_PERMISSIONS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_PERMISSIONS) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted && pendingAction != null) {
                pendingAction.run();
            } else {
                if (permissionResult != null) {
                    permissionResult.error("PERMISSION_DENIED",
                        "Required permissions were denied", null);
                    permissionResult = null;
                }
            }
            pendingAction = null;
        }
    }

    // ════════════════════════════════════════════════════════════
    //   LIFECYCLE
    // ════════════════════════════════════════════════════════════

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up Nearby connections to prevent resource leaks
        if (nearbyManager != null) {
            nearbyManager.stopAll();
        }
    }
}
