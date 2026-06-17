import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/auth_config.dart';

/// Returns the bearer token to attach to outgoing requests, or `null` for an
/// unauthenticated call. Async because the real token lives in secure storage.
typedef TokenProvider = Future<String?> Function();

class ApiClient {
  /// Hard cap on every request. Long enough that a slow first cold-start of
  /// the gateway still answers, short enough that the UI doesn't sit in a
  /// loading spinner indefinitely if the network drops mid-flight.
  static const Duration _timeout = Duration(seconds: 30);

  final TokenProvider _getToken;
  final http.Client _client;

  /// Constructs an ApiClient. Pass a custom [client] in tests
  /// (`package:http/testing.dart` `MockClient`) to drive responses without
  /// hitting the network. The default in-app constructor passes a real
  /// `http.Client`; production code should `close()` the client at app exit.
  ApiClient(this._getToken, {http.Client? client}) : _client = client ?? http.Client();

  Future<Map<String, String>> _headers() async {
    final t = await _getToken();
    return {
      'Content-Type': 'application/json',
      if (t != null) 'Authorization': 'Bearer $t',
    };
  }

  Future<http.Response> get(String path) async {
    final url = Uri.parse('${AuthConfig.apiBaseUrl}$path');
    return _client.get(url, headers: await _headers()).timeout(_timeout);
  }

  Future<http.Response> post(String path, Object body) async {
    final url = Uri.parse('${AuthConfig.apiBaseUrl}$path');
    return _client
        .post(url, headers: await _headers(), body: json.encode(body))
        .timeout(_timeout);
  }
}
