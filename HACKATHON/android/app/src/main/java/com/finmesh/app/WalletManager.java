package com.finmesh.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * ══════════════════════════════════════════════════════════════
 * WalletManager.java  — Phase 2: SharedPreferences Storage
 * ══════════════════════════════════════════════════════════════
 *
 * Handles all local wallet data:
 *   • Balance read / write
 *   • Transaction history (stored as JSON)
 *   • User identity (name, device ID)
 *
 * Uses SharedPreferences under the key "FinMeshWallet".
 * All write operations use .apply() (non-blocking, async disk write).
 */
public class WalletManager {

    // ── Constants ────────────────────────────────────────────────
    private static final String PREFS_NAME      = "FinMeshWallet";
    private static final String KEY_BALANCE     = "wallet_balance";
    private static final String KEY_USER_NAME   = "user_name";
    private static final String KEY_DEVICE_ID   = "device_id";
    private static final String KEY_TX_HISTORY  = "tx_history";
    private static final String KEY_TOTAL_SENT  = "total_amount_sent";
    private static final String KEY_TOTAL_RECV  = "total_amount_received";

    // ── Fields ───────────────────────────────────────────────────
    private final SharedPreferences prefs;
    private final SharedPreferences.Editor editor;
    private final Gson gson;

    // ── Singleton pattern so WalletManager is shared app-wide ────
    private static WalletManager instance;

    public static WalletManager getInstance(Context context) {
        if (instance == null) {
            instance = new WalletManager(context.getApplicationContext());
        }
        return instance;
    }

    private WalletManager(Context context) {
        prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        editor = prefs.edit();
        gson   = new Gson();
    }

    // ════════════════════════════════════════════════════════════
    //   BALANCE OPERATIONS
    // ════════════════════════════════════════════════════════════

    /**
     * Returns current wallet balance. Default = 1000.00 (demo seed money).
     */
    public float getBalance() {
        return prefs.getFloat(KEY_BALANCE, 1000.00f);
    }

    /**
     * Overwrites balance with a new value (used after a transaction).
     */
    public void setBalance(float newBalance) {
        editor.putFloat(KEY_BALANCE, newBalance);
        editor.apply();
    }

    /**
     * Debits an amount from the balance. Returns false if insufficient funds.
     */
    public boolean debit(float amount) {
        float current = getBalance();
        if (current < amount) return false;         // Insufficient funds
        setBalance(current - amount);
        addToTotalSent(amount);
        return true;
    }

    /**
     * Credits an incoming payment to the balance.
     */
    public void credit(float amount) {
        float current = getBalance();
        setBalance(current + amount);
        addToTotalReceived(amount);
    }

    // ════════════════════════════════════════════════════════════
    //   USER IDENTITY
    // ════════════════════════════════════════════════════════════

    public String getUserName() {
        return prefs.getString(KEY_USER_NAME, "FinMesh User");
    }

    public void setUserName(String name) {
        editor.putString(KEY_USER_NAME, name);
        editor.apply();
    }

    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, "DEVICE_UNSET");
    }

    public void setDeviceId(String deviceId) {
        editor.putString(KEY_DEVICE_ID, deviceId);
        editor.apply();
    }

    // ════════════════════════════════════════════════════════════
    //   TRANSACTION HISTORY (stored as JSON list)
    // ════════════════════════════════════════════════════════════

    /**
     * Appends a new transaction to the local history.
     * @param txId      Unique transaction identifier
     * @param amount    Amount involved
     * @param type      "SENT" or "RECEIVED"
     * @param party     The other party's name/device
     */
    public void recordTransaction(String txId, float amount, String type, String party) {
        List<Transaction> history = getTransactionHistory();
        history.add(0, new Transaction(txId, amount, type, party, System.currentTimeMillis()));
        // Keep only the last 50 transactions
        if (history.size() > 50) {
            history = history.subList(0, 50);
        }
        editor.putString(KEY_TX_HISTORY, gson.toJson(history));
        editor.apply();
    }

    /**
     * Returns the full transaction history, most recent first.
     */
    public List<Transaction> getTransactionHistory() {
        String json = prefs.getString(KEY_TX_HISTORY, "[]");
        Type listType = new TypeToken<List<Transaction>>(){}.getType();
        List<Transaction> list = gson.fromJson(json, listType);
        return list != null ? list : new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════
    //   STATS (Total sent / received across all time)
    // ════════════════════════════════════════════════════════════

    public float getTotalSent() {
        return prefs.getFloat(KEY_TOTAL_SENT, 0f);
    }

    public float getTotalReceived() {
        return prefs.getFloat(KEY_TOTAL_RECV, 0f);
    }

    private void addToTotalSent(float amount) {
        editor.putFloat(KEY_TOTAL_SENT, getTotalSent() + amount);
        editor.apply();
    }

    private void addToTotalReceived(float amount) {
        editor.putFloat(KEY_TOTAL_RECV, getTotalReceived() + amount);
        editor.apply();
    }

    // ════════════════════════════════════════════════════════════
    //   FULL RESET (for testing / logout)
    // ════════════════════════════════════════════════════════════

    public void clearAll() {
        editor.clear();
        editor.apply();
    }

    // ════════════════════════════════════════════════════════════
    //   INNER CLASS — Transaction Model
    // ════════════════════════════════════════════════════════════

    public static class Transaction {
        public String txId;
        public float  amount;
        public String type;       // "SENT" or "RECEIVED"
        public String party;      // Name/device of other side
        public long   timestamp;  // Unix epoch milliseconds

        public Transaction(String txId, float amount, String type, String party, long timestamp) {
            this.txId      = txId;
            this.amount    = amount;
            this.type      = type;
            this.party     = party;
            this.timestamp = timestamp;
        }
    }
}
