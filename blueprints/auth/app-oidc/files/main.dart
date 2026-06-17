import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'services/auth_service.dart';
import 'services/api_client.dart';

/// Catch every async/uncaught error so a single bad HTTP response (a 403
/// from a role-guarded endpoint, a TLS handshake failure, etc.) can't tear
/// the app process down. Without these handlers an unhandled Future
/// rejection cascades to the platform and Android kills the activity —
/// the symptom is an empty screen / app restart, not a useful message on
/// the Authenticated home page.
Future<void> main() async {
  // Reported synchronously inside the Flutter framework (build, layout,
  // paint). Logged but not rethrown so the framework can keep going.
  FlutterError.onError = (FlutterErrorDetails details) {
    debugPrint('FlutterError: ${details.exceptionAsString()}');
    if (kDebugMode) {
      FlutterError.dumpErrorToConsole(details);
    }
  };

  // Reported by the Dart runtime for errors that escape framework callbacks
  // (e.g. an unawaited Future thrown inside an isolate). Returning `true`
  // tells the platform we've handled it.
  PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
    debugPrint('PlatformDispatcher error: $error');
    return true;
  };

  // Run the whole app inside a guarded zone so anything that escapes the
  // two handlers above still surfaces here instead of hitting the OS.
  await runZonedGuarded<Future<void>>(() async {
    WidgetsFlutterBinding.ensureInitialized();
    runApp(const MyApp());
  }, (Object error, StackTrace stack) {
    debugPrint('Zone error: $error');
  });
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
  late final _apiClient = ApiClient(() => _authService.token);

  bool _loggedIn = false;
  bool _loading = false;
  List<String> _roles = [];
  String? _apiResponse;
  String? _error;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    // Complete any login interrupted by process death
    final recovered = await _authService.completePendingLogin();
    if (recovered) {
      final roles = await _authService.roles;
      setState(() {
        _loggedIn = true;
        _roles = roles;
      });
      return;
    }
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
    if (!mounted) return;
    setState(() { _apiResponse = null; _error = null; _loading = true; });
    try {
      final response = await _apiClient.get(path);
      if (!mounted) return;
      // Show every response — including 4xx/5xx — instead of treating
      // them as exceptions. The http package only throws on
      // socket/timeout errors; status codes are data, not crashes.
      final body = _safePreview(response.body);
      setState(() {
        _apiResponse = '${response.statusCode}: $body';
        _error = null;
      });
    } on TimeoutException {
      if (!mounted) return;
      setState(() {
        _error = 'Request timed out — is the gateway reachable?';
        _apiResponse = null;
      });
    } catch (e, stack) {
      debugPrint('_callApi failed for $path: $e\n$stack');
      if (!mounted) return;
      setState(() {
        _error = 'Request failed: $e';
        _apiResponse = null;
      });
    } finally {
      if (mounted) {
        setState(() { _loading = false; });
      }
    }
  }

  /// Cap response previews so an accidental megabyte payload can't blow up
  /// the widget tree. The full body is in the http response object if a
  /// caller needs it.
  static String _safePreview(String body) {
    const limit = 4096;
    if (body.length <= limit) return body;
    return '${body.substring(0, limit)}… (truncated, ${body.length} bytes total)';
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
              tooltip: 'Logout',
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
              label: const Text('GET /api/hello'),
              onPressed: () => _callApi('/api/hello'),
            ),
            FilledButton.tonalIcon(
              icon: const Icon(Icons.verified_user),
              label: const Text('GET /api/me'),
              onPressed: () => _callApi('/api/me'),
            ),
            FilledButton.tonalIcon(
              icon: const Icon(Icons.admin_panel_settings),
              label: const Text('GET /api/admin/users'),
              onPressed: () => _callApi('/api/admin/users'),
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
