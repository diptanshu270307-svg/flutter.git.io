// ══════════════════════════════════════════════════════════════
// main.dart — FinMesh Flutter UI
// ══════════════════════════════════════════════════════════════
//
// This is the complete Flutter front-end demonstrating ALL
// native bridge calls in a working UI:
//
//   • Home screen  → shows balance, recent transactions
//   • Pay screen   → enter amount, tap Pay, biometric fires
//   • Merchant     → one-tap to start receiving payments
//   • History      → scrollable transaction log
//
// ══════════════════════════════════════════════════════════════

import 'package:flutter/material.dart';
import 'native_bridge.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Initialize the bridge event listeners before runApp
  NativeBridge().initialize();
  runApp(const FinMeshApp());
}

// ────────────────────────────────────────────────────────────
//  App Root
// ────────────────────────────────────────────────────────────
class FinMeshApp extends StatelessWidget {
  const FinMeshApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FinMesh',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6C63FF),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        fontFamily: 'Inter',
      ),
      home: const HomeScreen(),
    );
  }
}

// ════════════════════════════════════════════════════════════
//  HOME SCREEN
// ════════════════════════════════════════════════════════════
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final NativeBridge _bridge = NativeBridge();

  double _balance = 0.0;
  String _userName = 'Loading...';
  String _statusMessage = 'Ready';
  List<Transaction> _transactions = [];
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _loadData();
    _setupEventListeners();
  }

  // ── Load wallet data on screen open ──────────────────────────
  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    try {
      final balance  = await _bridge.getBalance();
      final name     = await _bridge.getUserName();
      final txList   = await _bridge.getTransactions();
      setState(() {
        _balance      = balance;
        _userName     = name;
        _transactions = txList;
        _isLoading    = false;
      });
    } catch (e) {
      setState(() {
        _statusMessage = 'Error loading data: $e';
        _isLoading     = false;
      });
    }
  }

  // ── Listen to Java push events ────────────────────────────────
  void _setupEventListeners() {
    _bridge.onNearbyEvent = (event) {
      setState(() => _statusMessage = '📡 $event');
      if (event.startsWith('CONNECTED')) {
        setState(() => _statusMessage = '✅ Connected! Ready to pay or receive.');
      }
    };

    _bridge.onPaymentEvent = (event) {
      // Refresh data when a payment occurs
      _loadData();
      if (event.startsWith('RECEIVED')) {
        _showSnackBar('💰 Payment received!', Colors.green);
      } else if (event.startsWith('SENT')) {
        _showSnackBar('✅ Payment sent!', Colors.blue);
      }
    };
  }

  void _showSnackBar(String msg, Color color) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg),
        backgroundColor: color,
        duration: const Duration(seconds: 3),
      ),
    );
  }

  // ════════════════════════════════════════════════════════════
  //   BUILD
  // ════════════════════════════════════════════════════════════
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0F0F1A),
      body: SafeArea(
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: _loadData,
                child: ListView(
                  padding: const EdgeInsets.all(20),
                  children: [
                    _buildHeader(),
                    const SizedBox(height: 24),
                    _buildBalanceCard(),
                    const SizedBox(height: 16),
                    _buildStatusBanner(),
                    const SizedBox(height: 24),
                    _buildActionButtons(),
                    const SizedBox(height: 24),
                    _buildTransactionHistory(),
                  ],
                ),
              ),
      ),
    );
  }

  // ── Header ────────────────────────────────────────────────────
  Widget _buildHeader() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Welcome back,',
                style: TextStyle(color: Colors.grey, fontSize: 14)),
            Text(_userName,
                style: const TextStyle(
                    color: Colors.white,
                    fontSize: 22,
                    fontWeight: FontWeight.bold)),
          ],
        ),
        IconButton(
          icon: const Icon(Icons.refresh, color: Colors.white70),
          onPressed: _loadData,
        ),
      ],
    );
  }

  // ── Balance Card ──────────────────────────────────────────────
  Widget _buildBalanceCard() {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF6C63FF), Color(0xFF3B37C8)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(
            color: const Color(0xFF6C63FF).withOpacity(0.4),
            blurRadius: 20,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Wallet Balance',
              style: TextStyle(color: Colors.white70, fontSize: 14)),
          const SizedBox(height: 8),
          Text(
            '₹${_balance.toStringAsFixed(2)}',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 40,
              fontWeight: FontWeight.bold,
              letterSpacing: 1.2,
            ),
          ),
          const SizedBox(height: 16),
          // Demo: Add funds button
          GestureDetector(
            onTap: () async {
              final newBal = await _bridge.addDemoFunds(500.0);
              setState(() => _balance = newBal);
              _showSnackBar('Added ₹500 demo funds', Colors.purple);
            },
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.2),
                borderRadius: BorderRadius.circular(20),
              ),
              child: const Text('+ Add Demo Funds',
                  style: TextStyle(color: Colors.white, fontSize: 12)),
            ),
          ),
        ],
      ),
    );
  }

  // ── Status Banner ─────────────────────────────────────────────
  Widget _buildStatusBanner() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.05),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.white12),
      ),
      child: Row(
        children: [
          const Icon(Icons.info_outline, color: Colors.blueAccent, size: 16),
          const SizedBox(width: 8),
          Expanded(
            child: Text(_statusMessage,
                style: const TextStyle(color: Colors.white60, fontSize: 12)),
          ),
        ],
      ),
    );
  }

  // ── Action Buttons ────────────────────────────────────────────
  Widget _buildActionButtons() {
    return Row(
      children: [
        Expanded(
          child: _ActionButton(
            icon: Icons.send_rounded,
            label: 'Pay',
            color: const Color(0xFF6C63FF),
            onTap: () => _navigateToPay(),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: _ActionButton(
            icon: Icons.store_rounded,
            label: 'Receive',
            color: const Color(0xFF00C896),
            onTap: () => _startMerchantMode(),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: _ActionButton(
            icon: Icons.wifi_off_rounded,
            label: 'Stop',
            color: Colors.red.shade800,
            onTap: () async {
              await _bridge.stopNearby();
              setState(() => _statusMessage = 'Nearby stopped.');
            },
          ),
        ),
      ],
    );
  }

  // ── Transaction History ───────────────────────────────────────
  Widget _buildTransactionHistory() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Recent Transactions',
            style: TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.bold)),
        const SizedBox(height: 12),
        if (_transactions.isEmpty)
          const Center(
            child: Text('No transactions yet.',
                style: TextStyle(color: Colors.white38)),
          )
        else
          ...(_transactions.take(10).map((tx) => _TransactionTile(tx: tx))),
      ],
    );
  }

  // ── Navigation helpers ────────────────────────────────────────
  void _navigateToPay() {
    Navigator.push(context,
        MaterialPageRoute(builder: (_) => const PayScreen()));
  }

  Future<void> _startMerchantMode() async {
    try {
      await _bridge.startMerchantMode(_userName);
      setState(() => _statusMessage = '🏪 Merchant mode active. Waiting for payers...');
    } on PaymentException catch (e) {
      setState(() => _statusMessage = '❌ ${e.message}');
    }
  }
}

// ════════════════════════════════════════════════════════════
//  PAY SCREEN
// ════════════════════════════════════════════════════════════
class PayScreen extends StatefulWidget {
  const PayScreen({super.key});

  @override
  State<PayScreen> createState() => _PayScreenState();
}

class _PayScreenState extends State<PayScreen> {
  final NativeBridge _bridge = NativeBridge();
  final TextEditingController _amountController = TextEditingController();

  String _status = 'Scanning for merchants...';
  bool _isConnected = false;
  bool _isPaying = false;
  String _merchantName = '';

  @override
  void initState() {
    super.initState();
    _startDiscovery();
  }

  Future<void> _startDiscovery() async {
    _bridge.onNearbyEvent = (event) {
      setState(() {
        if (event.startsWith('MERCHANT_FOUND:')) {
          final parts = event.split(':');
          _merchantName = parts.length > 1 ? parts[1] : 'Merchant';
          _status = '✅ Found: $_merchantName. Connecting...';
        } else if (event.startsWith('CONNECTED')) {
          _isConnected = true;
          _status = '🟢 Connected to $_merchantName. Enter amount to pay.';
        } else {
          _status = event;
        }
      });
    };

    try {
      await _bridge.startPayerMode();
    } on PaymentException catch (e) {
      setState(() => _status = '❌ ${e.message}');
    }
  }

  Future<void> _pay() async {
    final amountStr = _amountController.text.trim();
    if (amountStr.isEmpty) return;

    final amount = double.tryParse(amountStr);
    if (amount == null || amount <= 0) {
      setState(() => _status = 'Please enter a valid amount.');
      return;
    }

    setState(() { _isPaying = true; _status = 'Waiting for fingerprint...'; });

    try {
      final txId = await _bridge.authenticateAndPay(
        amount:    amount,
        recipient: _merchantName,
      );
      setState(() {
        _isPaying = false;
        _status   = '✅ Payment sent! TX: $txId';
      });
    } on PaymentException catch (e) {
      setState(() {
        _isPaying = false;
        _status   = '❌ ${e.message}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0F0F1A),
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: const Text('Send Payment', style: TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Status indicator
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: _isConnected
                    ? Colors.green.withOpacity(0.1)
                    : Colors.orange.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: _isConnected ? Colors.green : Colors.orange,
                  width: 1,
                ),
              ),
              child: Text(
                _status,
                style: TextStyle(
                  color: _isConnected ? Colors.greenAccent : Colors.orangeAccent,
                  fontSize: 13,
                ),
              ),
            ),
            const SizedBox(height: 32),

            // Amount input
            const Text('Amount (₹)',
                style: TextStyle(color: Colors.white70, fontSize: 16)),
            const SizedBox(height: 8),
            TextField(
              controller: _amountController,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              style: const TextStyle(color: Colors.white, fontSize: 32),
              decoration: InputDecoration(
                hintText: '0.00',
                hintStyle: const TextStyle(color: Colors.white24),
                prefix: const Text('₹ ',
                    style: TextStyle(color: Colors.white54, fontSize: 32)),
                enabledBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Color(0xFF6C63FF))),
                focusedBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Color(0xFF6C63FF), width: 2)),
              ),
            ),
            const SizedBox(height: 40),

            // Pay button
            SizedBox(
              width: double.infinity,
              height: 60,
              child: ElevatedButton(
                onPressed: (!_isConnected || _isPaying) ? null : _pay,
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF6C63FF),
                  disabledBackgroundColor: Colors.grey.shade800,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                ),
                child: _isPaying
                    ? const CircularProgressIndicator(color: Colors.white)
                    : const Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.fingerprint, color: Colors.white, size: 28),
                          SizedBox(width: 12),
                          Text('Authenticate & Pay',
                              style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 18,
                                  fontWeight: FontWeight.bold)),
                        ],
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _amountController.dispose();
    super.dispose();
  }
}

// ════════════════════════════════════════════════════════════
//  REUSABLE WIDGETS
// ════════════════════════════════════════════════════════════

class _ActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  const _ActionButton({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 16),
        decoration: BoxDecoration(
          color: color.withOpacity(0.15),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: color.withOpacity(0.4)),
        ),
        child: Column(
          children: [
            Icon(icon, color: color, size: 28),
            const SizedBox(height: 6),
            Text(label,
                style: TextStyle(
                    color: color, fontSize: 13, fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}

class _TransactionTile extends StatelessWidget {
  final Transaction tx;
  const _TransactionTile({required this.tx});

  @override
  Widget build(BuildContext context) {
    final isSent = tx.type == 'SENT';
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white.withOpacity(0.04),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withOpacity(0.07)),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: (isSent ? Colors.redAccent : Colors.greenAccent)
                  .withOpacity(0.15),
              shape: BoxShape.circle,
            ),
            child: Icon(
              isSent ? Icons.arrow_upward : Icons.arrow_downward,
              color: isSent ? Colors.redAccent : Colors.greenAccent,
              size: 16,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(tx.party,
                    style: const TextStyle(
                        color: Colors.white, fontWeight: FontWeight.w600)),
                Text(tx.txId,
                    style: const TextStyle(color: Colors.white38, fontSize: 11)),
              ],
            ),
          ),
          Text(
            '${isSent ? "-" : "+"}₹${tx.amount.toStringAsFixed(2)}',
            style: TextStyle(
              color: isSent ? Colors.redAccent : Colors.greenAccent,
              fontWeight: FontWeight.bold,
              fontSize: 16,
            ),
          ),
        ],
      ),
    );
  }
}
