import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/auth_config.dart';
import 'auth_service.dart';

class ApiClient {
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
    return http.get(url, headers: await _headers());
  }

  Future<http.Response> post(String path, Object body) async {
    final url = Uri.parse('${AuthConfig.apiBaseUrl}$path');
    return http.post(url, headers: await _headers(), body: json.encode(body));
  }
}
