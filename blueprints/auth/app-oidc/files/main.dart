import 'package:flutter/material.dart';

import 'services/auth_service.dart';
import 'services/api_client.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Phantom Token Demo',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _authService = AuthService();
  late final _apiClient = ApiClient(_authService);

  bool _loggedIn = false;
  bool _loading = false;
  List<String> _roles = [];
  String? _apiResponse;
  String? _error;

  @override
  void initState() {
    super.initState();
    _checkAuth();
  }

  Future<void> _checkAuth() async {
    final authed = await _authService.isAuthenticated;
    if (authed) {
      final roles = await _authService.roles;
      setState(() {
        _loggedIn = true;
        _roles = roles;
      });
    }
  }

  Future<void> _login() async {
    setState(() { _loading = true; _error = null; });
    try {
      final success = await _authService.login();
      if (success) {
        final roles = await _authService.roles;
        setState(() { _loggedIn = true; _roles = roles; });
      }
    } catch (e) {
      setState(() { _error = e.toString(); });
    } finally {
      setState(() { _loading = false; });
    }
  }

  Future<void> _logout() async {
    await _authService.logout();
    setState(() {
      _loggedIn = false;
      _roles = [];
      _apiResponse = null;
    });
  }

  Future<void> _callApi(String path) async {
    setState(() { _apiResponse = null; _error = null; _loading = true; });
    try {
      final response = await _apiClient.get(path);
      setState(() { _apiResponse = '${response.statusCode}: ${response.body}'; });
    } catch (e) {
      setState(() { _error = e.toString(); });
    } finally {
      setState(() { _loading = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Phantom Token Demo'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        actions: [
          if (_loggedIn)
            IconButton(
              icon: const Icon(Icons.logout),
              onPressed: _logout,
            ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: _loggedIn ? _buildLoggedIn() : _buildLogin(),
      ),
    );
  }

  Widget _buildLogin() {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.lock_outline, size: 64, color: Colors.deepPurple),
          const SizedBox(height: 24),
          const Text(
            'Sign in with Curity',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          const Text(
            'Uses OIDC + phantom token pattern via Kong',
            style: TextStyle(color: Colors.grey),
          ),
          const SizedBox(height: 32),
          if (_loading)
            const CircularProgressIndicator()
          else
            FilledButton.icon(
              icon: const Icon(Icons.login),
              label: const Text('Login'),
              onPressed: _login,
            ),
          if (_error != null) ...[
            const SizedBox(height: 16),
            Text(_error!, style: const TextStyle(color: Colors.red)),
          ],
        ],
      ),
    );
  }

  Widget _buildLoggedIn() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Card(
          child: ListTile(
            leading: const Icon(Icons.person, color: Colors.deepPurple),
            title: const Text('Authenticated'),
            subtitle: Text('Roles: ${_roles.isEmpty ? "none" : _roles.join(", ")}'),
          ),
        ),
        const SizedBox(height: 16),
        const Text('Test API calls (through Kong phantom token):',
            style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.tonalIcon(
              icon: const Icon(Icons.public),
              label: const Text('GET /api/public'),
              onPressed: () => _callApi('/api/public'),
            ),
            FilledButton.tonalIcon(
              icon: const Icon(Icons.verified_user),
              label: const Text('GET /api/protected'),
              onPressed: () => _callApi('/api/protected'),
            ),
            FilledButton.tonalIcon(
              icon: const Icon(Icons.admin_panel_settings),
              label: const Text('GET /api/admin'),
              onPressed: () => _callApi('/api/admin'),
            ),
          ],
        ),
        if (_loading) ...[
          const SizedBox(height: 16),
          const CircularProgressIndicator(),
        ],
        if (_apiResponse != null) ...[
          const SizedBox(height: 16),
          Card(
            color: Colors.grey.shade100,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: SelectableText(_apiResponse!,
                  style: const TextStyle(fontFamily: 'monospace')),
            ),
          ),
        ],
        if (_error != null) ...[
          const SizedBox(height: 16),
          Text(_error!, style: const TextStyle(color: Colors.red)),
        ],
      ],
    );
  }
}
