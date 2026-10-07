package com.finmesh.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ══════════════════════════════════════════════════════════════
 * NearbyConnectionsManager.java  — Phase 3: Offline P2P
 * ══════════════════════════════════════════════════════════════
 *
 * Manages Google Nearby Connections for completely offline
 * peer-to-peer payment communication between two devices.
 *
 * STRATEGY: P2P_STAR
 *   • Merchant device  → calls startAdvertising()
 *   • Payer device     → calls startDiscovery()
 *   • Both             → connection auto-accepted
 *   • Payment data     → sent as bytes via Payload.fromBytes()
 *
 * SERVICE ID: Must be identical on BOTH devices!
 *             "com.finmesh.app" ← this string is the handshake key
 *
 * USAGE (Merchant side):
 *   manager.startAdvertising("ShopName");
 *
 * USAGE (Payer side):
 *   manager.startDiscovery();
 *   // When a merchant is found → connect automatically
 *   // Then:
 *   manager.sendPayment(endpointId, amount, senderName);
 */
public class NearbyConnectionsManager {

    private static final String TAG        = "NearbyManager";
    private static final String SERVICE_ID = "com.finmesh.app";  // ← MUST match on both devices

    // ── Nearby Client ────────────────────────────────────────────
    private final ConnectionsClient connectionsClient;
    private final Context context;

    // ── State tracking ───────────────────────────────────────────
    private String connectedEndpointId = null;   // ID of the currently connected peer
    private boolean isAdvertising   = false;
    private boolean isDiscovering   = false;

    // ── Callback (to notify your Activity/ViewModel) ─────────────
    private final NearbyCallback callback;

    // ── Constructor ──────────────────────────────────────────────
    public NearbyConnectionsManager(Context context, NearbyCallback callback) {
        this.context           = context;
        this.callback          = callback;
        this.connectionsClient = Nearby.getConnectionsClient(context);
    }

    // ════════════════════════════════════════════════════════════
    //   1. MERCHANT MODE — startAdvertising()
    // ════════════════════════════════════════════════════════════

    /**
     * Puts this device in MERCHANT mode — it announces its presence
     * over Bluetooth/Wi-Fi Direct so payer devices can find it.
     *
     * @param merchantName Human-readable shop/merchant name
     */
    public void startAdvertising(String merchantName) {
        if (isAdvertising) {
            Log.w(TAG, "Already advertising. Ignoring duplicate call.");
            return;
        }

        AdvertisingOptions options = new AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)   // 1 host, many clients
            .build();

        connectionsClient.startAdvertising(
            merchantName,                        // Endpoint name shown to payers
            SERVICE_ID,                          // Must match startDiscovery()!
            connectionLifecycleCallback,         // Handles connect/disconnect events
            options
        )
        .addOnSuccessListener(unused -> {
            isAdvertising = true;
            Log.d(TAG, "✅ Advertising started as: " + merchantName);
            callback.onAdvertisingStarted();
        })
        .addOnFailureListener(e -> {
            Log.e(TAG, "❌ Advertising failed: " + e.getMessage());
            callback.onError("Advertising failed: " + e.getMessage());
        });
    }

    // ════════════════════════════════════════════════════════════
    //   2. PAYER MODE — startDiscovery()
    // ════════════════════════════════════════════════════════════

    /**
     * Puts this device in PAYER mode — it scans for merchant devices.
     * When a merchant is found, it automatically initiates a connection.
     */
    public void startDiscovery() {
        if (isDiscovering) {
            Log.w(TAG, "Already discovering. Ignoring duplicate call.");
            return;
        }

        DiscoveryOptions options = new DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build();

        connectionsClient.startDiscovery(
            SERVICE_ID,                          // Must match startAdvertising()!
            endpointDiscoveryCallback,           // Fires when merchants are found/lost
            options
        )
        .addOnSuccessListener(unused -> {
            isDiscovering = true;
            Log.d(TAG, "✅ Discovery started. Scanning for merchants...");
            callback.onDiscoveryStarted();
        })
        .addOnFailureListener(e -> {
            Log.e(TAG, "❌ Discovery failed: " + e.getMessage());
            callback.onError("Discovery failed: " + e.getMessage());
        });
    }

    // ════════════════════════════════════════════════════════════
    //   3. SEND PAYMENT — Payload.fromBytes()
    // ════════════════════════════════════════════════════════════

    /**
     * Serializes a payment into a byte payload and sends it to the
     * connected endpoint (merchant).
     *
     * Payload format (pipe-delimited):
     *   "FINMESH_PAY|TX_<uuid>|FROM:<name>|AMOUNT:<float>|TS:<epoch>"
     *
     * @param endpointId  The remote device's endpoint ID
     * @param amount      Payment amount (e.g., 500.00)
     * @param senderName  Name of the person paying
     */
    public void sendPayment(String endpointId, float amount, String senderName) {
        if (endpointId == null) {
            callback.onError("No connected device to send payment to.");
            return;
        }

        // Build the payment payload string
        String txId = "TX_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String payloadStr = "FINMESH_PAY"
            + "|" + txId
            + "|FROM:" + senderName
            + "|AMOUNT:" + amount
            + "|TS:" + System.currentTimeMillis();

        // Convert string → bytes and wrap in Payload
        byte[] payloadBytes = payloadStr.getBytes(StandardCharsets.UTF_8);
        Payload payload = Payload.fromBytes(payloadBytes);

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener(unused -> {
                Log.d(TAG, "✅ Payment payload sent: " + payloadStr);
                callback.onPaymentSent(txId, amount);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Payment send failed: " + e.getMessage());
                callback.onError("Send failed: " + e.getMessage());
            });
    }

    /**
     * Sends a generic string message (for confirmations, ACKs, etc.)
     */
    public void sendMessage(String endpointId, String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes));
    }

    // ════════════════════════════════════════════════════════════
    //   4. CONNECTION LIFECYCLE CALLBACK
    //      (both devices use this — it handles the handshake)
    // ════════════════════════════════════════════════════════════

    private final ConnectionLifecycleCallback connectionLifecycleCallback =
        new ConnectionLifecycleCallback() {

        /**
         * Fired on both devices when a connection request arrives.
         * For hackathon: auto-accept immediately.
         * For production: show a confirmation dialog first.
         */
        @Override
        public void onConnectionInitiated(
                @NonNull String endpointId,
                @NonNull ConnectionInfo info) {
            Log.d(TAG, "Connection initiated with: " + info.getEndpointName()
                + " [" + endpointId + "]");

            // Auto-accept the connection for hackathon demo
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener(unused ->
                    Log.d(TAG, "Accepted connection from: " + info.getEndpointName())
                )
                .addOnFailureListener(e ->
                    Log.e(TAG, "Failed to accept connection: " + e.getMessage())
                );
        }

        /**
         * Fired when the connection is fully established (both sides accepted).
         */
        @Override
        public void onConnectionResult(
                @NonNull String endpointId,
                @NonNull ConnectionResolution resolution) {
            if (resolution.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpointId = endpointId;
                Log.d(TAG, "✅ Connected to endpoint: " + endpointId);
                callback.onConnected(endpointId);
            } else {
                Log.e(TAG, "Connection rejected: " + resolution.getStatus());
                callback.onError("Connection failed: " + resolution.getStatus().getStatusMessage());
            }
        }

        /**
         * Fired when a device disconnects.
         */
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.d(TAG, "Disconnected from: " + endpointId);
            if (endpointId.equals(connectedEndpointId)) {
                connectedEndpointId = null;
            }
            callback.onDisconnected(endpointId);
        }
    };

    // ════════════════════════════════════════════════════════════
    //   5. ENDPOINT DISCOVERY CALLBACK (Payer side only)
    // ════════════════════════════════════════════════════════════

    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
        new EndpointDiscoveryCallback() {

        /**
         * A merchant device was found within range.
         */
        @Override
        public void onEndpointFound(
                @NonNull String endpointId,
                @NonNull DiscoveredEndpointInfo info) {
            Log.d(TAG, "Merchant found: " + info.getEndpointName() + " [" + endpointId + "]");
            callback.onMerchantFound(endpointId, info.getEndpointName());

            // Auto-connect to the first found merchant (for hackathon demo)
            connectionsClient.requestConnection(
                "FinMesh-Payer",               // This device's name
                endpointId,                     // Merchant's endpoint ID
                connectionLifecycleCallback
            )
            .addOnSuccessListener(unused ->
                Log.d(TAG, "Connection requested to: " + info.getEndpointName())
            )
            .addOnFailureListener(e ->
                Log.e(TAG, "Connection request failed: " + e.getMessage())
            );
        }

        /**
         * A previously found merchant went out of range.
         */
        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Log.d(TAG, "Merchant lost: " + endpointId);
            callback.onMerchantLost(endpointId);
        }
    };

    // ════════════════════════════════════════════════════════════
    //   6. PAYLOAD CALLBACK (receives incoming data on BOTH sides)
    // ════════════════════════════════════════════════════════════

    private final PayloadCallback payloadCallback = new PayloadCallback() {

        /**
         * A payload has been fully received from the remote endpoint.
         * Parse the payment data here.
         */
        @Override
        public void onPayloadReceived(
                @NonNull String endpointId,
                @NonNull Payload payload) {

            if (payload.getType() == Payload.Type.BYTES) {
                byte[] bytes = payload.asBytes();
                if (bytes == null) return;

                String rawMessage = new String(bytes, StandardCharsets.UTF_8);
                Log.d(TAG, "📦 Received payload from [" + endpointId + "]: " + rawMessage);

                // Parse FinMesh payment format
                if (rawMessage.startsWith("FINMESH_PAY")) {
                    parseAndHandlePayment(endpointId, rawMessage);
                } else {
                    // Generic message (ACK, status, etc.)
                    callback.onMessageReceived(endpointId, rawMessage);
                }
            }
        }

        /**
         * Progress updates for large payloads. For small payment strings, this
         * fires only once with SUCCESS status.
         */
        @Override
        public void onPayloadTransferUpdate(
                @NonNull String endpointId,
                @NonNull PayloadTransferUpdate update) {
            if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "Payload transfer complete to/from: " + endpointId);
            }
        }
    };

    // ════════════════════════════════════════════════════════════
    //   7. PAYMENT PARSER
    // ════════════════════════════════════════════════════════════

    /**
     * Parses the pipe-delimited payment string and notifies the callback.
     * Format: "FINMESH_PAY|TX_XXXX|FROM:Name|AMOUNT:500.0|TS:1234567890"
     */
    private void parseAndHandlePayment(String endpointId, String raw) {
        try {
            String[] parts = raw.split("\\|");
            // parts[0] = "FINMESH_PAY"
            // parts[1] = "TX_XXXXXXXX"
            // parts[2] = "FROM:UserName"
            // parts[3] = "AMOUNT:500.0"
            // parts[4] = "TS:1234567890"

            String txId       = parts[1];
            String senderName = parts[2].replace("FROM:", "");
            float  amount     = Float.parseFloat(parts[3].replace("AMOUNT:", ""));
            long   timestamp  = Long.parseLong(parts[4].replace("TS:", ""));

            callback.onPaymentReceived(endpointId, txId, senderName, amount, timestamp);

            // Send an ACK back to the payer
            sendMessage(endpointId, "FINMESH_ACK|" + txId + "|STATUS:OK");

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse payment payload: " + e.getMessage());
            callback.onError("Invalid payment data received.");
        }
    }

    // ════════════════════════════════════════════════════════════
    //   8. CLEANUP
    // ════════════════════════════════════════════════════════════

    /**
     * Stops all advertising/discovering and disconnects all endpoints.
     * Call this in onDestroy() or when the payment session ends.
     */
    public void stopAll() {
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectionsClient.stopAllEndpoints();
        isAdvertising        = false;
        isDiscovering        = false;
        connectedEndpointId  = null;
        Log.d(TAG, "Nearby Connections stopped.");
    }

    // Getters
    public String getConnectedEndpointId()  { return connectedEndpointId; }
    public boolean isConnected()            { return connectedEndpointId != null; }

    // ════════════════════════════════════════════════════════════
    //   PERMISSIONS CHECK (static utility)
    // ════════════════════════════════════════════════════════════

    /**
     * Returns a list of permissions that have NOT been granted yet.
     * If the list is empty, you are good to start Nearby.
     */
    public static List<String> getMissingPermissions(Context context) {
        List<String> missing = new ArrayList<>();

        String[] required;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // Android 13+
            required = new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {  // Android 12
            required = new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {  // Android 11 and below
            required = new String[]{
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        for (String perm : required) {
            if (ContextCompat.checkSelfPermission(context, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        return missing;
    }

    // ════════════════════════════════════════════════════════════
    //   CALLBACK INTERFACE
    // ════════════════════════════════════════════════════════════

    /**
     * Implement this interface in your Activity/ViewModel to respond
     * to all Nearby Connections events.
     */
    public interface NearbyCallback {

        // ── Connection Events ────────────────────────────────────
        void onAdvertisingStarted();
        void onDiscoveryStarted();
        void onConnected(String endpointId);
        void onDisconnected(String endpointId);

        // ── Discovery Events (Payer side) ────────────────────────
        void onMerchantFound(String endpointId, String merchantName);
        void onMerchantLost(String endpointId);

        // ── Payment Events ───────────────────────────────────────
        /** Called on the PAYER device after successfully sending. */
        void onPaymentSent(String txId, float amount);

        /** Called on the MERCHANT device when money arrives. */
        void onPaymentReceived(String endpointId, String txId,
                               String senderName, float amount, long timestamp);

        // ── Generic Message ──────────────────────────────────────
        void onMessageReceived(String endpointId, String message);

        // ── Errors ───────────────────────────────────────────────
        void onError(String errorMessage);
    }
}
