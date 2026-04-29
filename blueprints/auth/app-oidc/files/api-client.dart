import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/auth_config.dart';
import 'auth_service.dart';

class ApiClient {
  /// Hard cap on every request. Long enough that a slow first cold-start of
  /// the gateway still answers, short enough that the UI doesn't sit in a
  /// loading spinner indefinitely if the network drops mid-flight.
  static const Duration _timeout = Duration(seconds: 30);

  final AuthService _authService;

  ApiClient(this._authService);

  Future<Map<String, String>> _headers() async {
    final t = await _authService.token;
    return {
      'Content-Type': 'application/json',
      if (t != null) 'Authorization': 'Bearer $t',
    };
  }

  Future<http.Response> get(String path) async {
    final url = Uri.parse('${AuthConfig.apiBaseUrl}$path');
    return http.get(url, headers: await _headers()).timeout(_timeout);
  }

  Future<http.Response> post(String path, Object body) async {
    final url = Uri.parse('${AuthConfig.apiBaseUrl}$path');
    return http
        .post(url, headers: await _headers(), body: json.encode(body))
        .timeout(_timeout);
  }
}
