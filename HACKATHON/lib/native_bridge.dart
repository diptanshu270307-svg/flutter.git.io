// ══════════════════════════════════════════════════════════════
// native_bridge.dart  — Phase 4: Flutter ↔ Java MethodChannel
// ══════════════════════════════════════════════════════════════
//
// This is the Dart (Flutter) side of the bridge.
// It wraps every Java method into a clean async Dart function
// that your Flutter UI widgets can call directly.
//
// CHANNEL KEY: "com.finmesh/native"  ← must match MainActivity.java
//
// HOW TO USE IN A WIDGET:
//   final bridge = NativeBridge();
//
//   // Get balance:
//   double balance = await bridge.getBalance();
//
//   // Pay someone:
//   try {
//     String txId = await bridge.authenticateAndPay(500.0, "ShopXYZ");
//     print("Paid! TX: $txId");
//   } on PaymentException catch (e) {
//     print("Failed: ${e.message}");
//   }
// ══════════════════════════════════════════════════════════════

import 'dart:convert';
import 'package:flutter/services.dart';

// ────────────────────────────────────────────────────────────
//  Custom Exception for payment errors
// ────────────────────────────────────────────────────────────
class PaymentException implements Exception {
  final String code;
  final String message;
  const PaymentException({required this.code, required this.message});

  @override
  String toString() => 'PaymentException[$code]: $message';
}

// ────────────────────────────────────────────────────────────
//  Transaction model (mirrors WalletManager.Transaction)
// ────────────────────────────────────────────────────────────
class Transaction {
  final String txId;
  final double amount;
  final String type;        // "SENT" or "RECEIVED"
  final String party;
  final DateTime timestamp;

  Transaction({
    required this.txId,
    required this.amount,
    required this.type,
    required this.party,
    required this.timestamp,
  });

  factory Transaction.fromJson(Map<String, dynamic> json) {
    return Transaction(
      txId:      json['txId'] as String,
      amount:    (json['amount'] as num).toDouble(),
      type:      json['type'] as String,
      party:     json['party'] as String,
      timestamp: DateTime.fromMillisecondsSinceEpoch(json['timestamp'] as int),
    );
  }
}

// ────────────────────────────────────────────────────────────
//  NativeBridge — Main Bridge Class
// ────────────────────────────────────────────────────────────
class NativeBridge {
  // Singleton
  static final NativeBridge _instance = NativeBridge._internal();
  factory NativeBridge() => _instance;
  NativeBridge._internal();

  // Channel — must match CHANNEL constant in MainActivity.java
  static const MethodChannel _channel = MethodChannel('com.finmesh/native');

  // Event listeners (for push events FROM Java)
  Function(String event)? onNearbyEvent;
  Function(String event)? onPaymentEvent;

  // ── Set up listeners for Java → Flutter push events ──────────
  void initialize() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'nearbyEvent':
          final event = call.arguments as String;
          onNearbyEvent?.call(event);
          break;
        case 'paymentEvent':
          final event = call.arguments as String;
          onPaymentEvent?.call(event);
          break;
      }
      return null;
    });
  }

  // ════════════════════════════════════════════════════════════
  //   WALLET OPERATIONS
  // ════════════════════════════════════════════════════════════

  /// Returns the current wallet balance in INR (₹).
  Future<double> getBalance() async {
    try {
      final double balance = await _channel.invokeMethod('getBalance');
      return balance;
    } on PlatformException catch (e) {
      throw PaymentException(code: e.code, message: e.message ?? 'Unknown error');
    }
  }

  /// Returns a list of past transactions, most recent first.
  Future<List<Transaction>> getTransactions() async {
    try {
      final String jsonStr = await _channel.invokeMethod('getTransactions');
      final List<dynamic> list = jsonDecode(jsonStr);
      return list.map((e) => Transaction.fromJson(e as Map<String, dynamic>)).toList();
    } on PlatformException catch (e) {
      throw PaymentException(code: e.code, message: e.message ?? 'Unknown error');
    }
  }

  /// Adds demo funds to the wallet (for testing only).
  Future<double> addDemoFunds(double amount) async {
    try {
      final double newBalance = await _channel.invokeMethod(
        'addDemoFunds',
        {'amount': amount},
      );
      return newBalance;
    } on PlatformException catch (e) {
      throw PaymentException(code: e.code, message: e.message ?? 'Unknown error');
    }
  }

  /// Sets the user's display name.
  Future<void> setUserName(String name) async {
    await _channel.invokeMethod('setUserName', {'name': name});
  }

  /// Gets the user's display name.
  Future<String> getUserName() async {
    final String name = await _channel.invokeMethod('getUserName');
    return name;
  }

  // ════════════════════════════════════════════════════════════
  //   PAYMENT (Biometric + Nearby combined)
  // ════════════════════════════════════════════════════════════

  /// Triggers biometric auth and, on success, sends payment over Nearby.
  ///
  /// Returns the transaction ID (e.g., "TX_ABC123DEF456") on success.
  /// Throws [PaymentException] on failure (cancelled, insufficient funds, etc.)
  ///
  /// Prerequisites:
  ///   • startPayerMode() must have been called
  ///   • A merchant must be connected (isConnected() == true)
  Future<String> authenticateAndPay({
    required double amount,
    required String recipient,
  }) async {
    try {
      final String txId = await _channel.invokeMethod(
        'authenticateAndPay',
        {
          'amount':    amount,
          'recipient': recipient,
        },
      );
      return txId;
    } on PlatformException catch (e) {
      throw PaymentException(
        code:    e.code,
        message: e.message ?? 'Payment failed',
      );
    }
  }

  // ════════════════════════════════════════════════════════════
  //   NEARBY CONNECTIONS
  // ════════════════════════════════════════════════════════════

  /// Starts MERCHANT mode — this device advertises itself for payment.
  /// Listen to [onNearbyEvent] for "CONNECTED:endpointId" events.
  Future<void> startMerchantMode(String merchantName) async {
    try {
      await _channel.invokeMethod('startMerchantMode', {'merchantName': merchantName});
    } on PlatformException catch (e) {
      throw PaymentException(code: e.code, message: e.message ?? 'Merchant mode failed');
    }
  }

  /// Starts PAYER mode — this device scans for merchants.
  /// Listen to [onNearbyEvent] for "MERCHANT_FOUND:name:id" and
  /// "CONNECTED:endpointId" events.
  Future<void> startPayerMode() async {
    try {
      await _channel.invokeMethod('startPayerMode');
    } on PlatformException catch (e) {
      throw PaymentException(code: e.code, message: e.message ?? 'Payer mode failed');
    }
  }

  /// Stops all Nearby advertising, discovery, and disconnects endpoints.
  Future<void> stopNearby() async {
    await _channel.invokeMethod('stopNearby');
  }

  /// Returns true if a remote device is currently connected.
  Future<bool> isConnected() async {
    final bool connected = await _channel.invokeMethod('isConnected');
    return connected;
  }
}
