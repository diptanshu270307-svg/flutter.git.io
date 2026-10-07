package com.finmesh.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragme
ntActivity;

import java.util.concurrent.Executor;

/**
 * ══════════════════════════════════════════════════════════════
 * BiometricHelper.java  — Phase 2: Biometric Authentication
 * ══════════════════════════════════════════════════════════════
 *
 * Wraps Android Jetpack BiometricPrompt into a clean helper.
 *
 * Usage:
 *   BiometricHelper helper = new BiometricHelper(activity);
 *   if (helper.isBiometricAvailable()) {
 *       helper.authenticate("Confirm Payment", callback);
 *   }
 *
 * Callbacks:
 *   BiometricCallback.onSuccess()   → payment authorized, proceed
 *   BiometricCallback.onFailure()   → fingerprint rejected, show error
 *   BiometricCallback.onError()     → hardware/system error
 */
public class BiometricHelper {

    private static final String TAG = "BiometricHelper";

    // ── Fields ───────────────────────────────────────────────────
    private final FragmentActivity activity;
    private final Executor executor;

    // ── Constructor ──────────────────────────────────────────────
    public BiometricHelper(FragmentActivity activity) {
        this.activity = activity;
        this.executor = ContextCompat.getMainExecutor(activity);
    }

    // ════════════════════════════════════════════════════════════
    //   PUBLIC INTERFACE
    // ════════════════════════════════════════════════════════════

    /**
     * Checks if the device has working biometric hardware + enrolled credentials.
     * Call this BEFORE authenticate() to handle the no-hardware case gracefully.
     */
    public boolean isBiometricAvailable() {
        BiometricManager biometricManager = BiometricManager.from(activity);
        int result = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG |
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        );

        switch (result) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                Log.d(TAG, "Biometric hardware available and enrolled.");
                return true;

            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                Log.w(TAG, "No biometric hardware on this device.");
                return false;

            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                Log.w(TAG, "Biometric hardware currently unavailable.");
                return false;

            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                Log.w(TAG, "No biometric credentials enrolled.");
                return false;

            default:
                return false;
        }
    }

    /**
     * Launches the biometric authentication dialog.
     *
     * @param title       Title shown at top of the dialog (e.g., "Authorize Payment")
     * @param subtitle    Subtitle (e.g., "Amount: ₹500.00 → MerchantXYZ")
     * @param callback    Your BiometricCallback implementation
     */
    public void authenticate(String title, String subtitle, BiometricCallback callback) {

        // ── Step 1: Build the AuthenticationCallback ─────────────
        BiometricPrompt.AuthenticationCallback authCallback =
            new BiometricPrompt.AuthenticationCallback() {

            @Override
            public void onAuthenticationSucceeded(
                    @NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                Log.d(TAG, "Authentication SUCCEEDED");
                callback.onSuccess();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                // Note: This fires for a wrong fingerprint — the dialog stays open.
                // Do NOT dismiss the dialog here. Android handles retry automatically.
                Log.w(TAG, "Authentication FAILED (wrong biometric)");
                callback.onFailure();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Log.e(TAG, "Authentication ERROR: [" + errorCode + "] " + errString);
                callback.onError(errorCode, errString.toString());
            }
        };

        // ── Step 2: Build the PromptInfo UI ──────────────────────
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription("Use your fingerprint or face ID to authorize this transaction")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            // Note: setNegativeButtonText() is mutually exclusive with DEVICE_CREDENTIAL.
            // If you want a "Cancel" button only (no PIN fallback), use:
            //   .setNegativeButtonText("Cancel")
            // and remove DEVICE_CREDENTIAL from setAllowedAuthenticators above.
            .build();

        // ── Step 3: Create prompt and authenticate ────────────────
        BiometricPrompt biometricPrompt = new BiometricPrompt(activity, executor, authCallback);
        biometricPrompt.authenticate(promptInfo);
    }

    // ════════════════════════════════════════════════════════════
    //   CALLBACK INTERFACE
    // ════════════════════════════════════════════════════════════

    /**
     * Implement this interface to handle auth outcomes.
     */
    public interface BiometricCallback {
        /** Called when fingerprint/face was accepted. Proceed with payment here. */
        void onSuccess();

        /** Called when a biometric was scanned but didn't match (dialog stays open). */
        void onFailure();

        /**
         * Called on a hard error (user cancelled, too many attempts, hardware failure).
         * @param errorCode  Android's error constant (e.g., ERROR_USER_CANCELED = 13)
         * @param errorMsg   Human-readable error description
         */
        void onError(int errorCode, String errorMsg);
    }
}
