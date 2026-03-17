import 'dart:convert';

import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../config/auth_config.dart';

class AuthService {
  final FlutterAppAuth _appAuth = const FlutterAppAuth();
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  static const _accessTokenKey = 'access_token';
  static const _refreshTokenKey = 'refresh_token';
  static const _idTokenKey = 'id_token';

  Future<bool> login() async {
    final result = await _appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        AuthConfig.clientId,
        AuthConfig.redirectUri,
        issuer: AuthConfig.issuer,
        scopes: AuthConfig.scopes,
        allowInsecureConnections: true,
      ),
    );

    if (result == null) return false;

    await _storage.write(key: _accessTokenKey, value: result.accessToken);
    await _storage.write(key: _refreshTokenKey, value: result.refreshToken);
    await _storage.write(key: _idTokenKey, value: result.idToken);
    return true;
  }

  Future<void> logout() async {
    await _storage.delete(key: _accessTokenKey);
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _idTokenKey);
  }

  Future<String?> refreshToken() async {
    final stored = await _storage.read(key: _refreshTokenKey);
    if (stored == null) return null;

    final result = await _appAuth.token(
      TokenRequest(
        AuthConfig.clientId,
        AuthConfig.redirectUri,
        issuer: AuthConfig.issuer,
        refreshToken: stored,
        allowInsecureConnections: true,
      ),
    );

    if (result == null) return null;

    await _storage.write(key: _accessTokenKey, value: result.accessToken);
    if (result.refreshToken != null) {
      await _storage.write(key: _refreshTokenKey, value: result.refreshToken);
    }
    if (result.idToken != null) {
      await _storage.write(key: _idTokenKey, value: result.idToken);
    }
    return result.accessToken;
  }

  Future<String?> get token async {
    return await _storage.read(key: _accessTokenKey);
  }

  Future<bool> get isAuthenticated async {
    final t = await _storage.read(key: _accessTokenKey);
    return t != null;
  }

  Future<List<String>> get roles async {
    final idToken = await _storage.read(key: _idTokenKey);
    if (idToken == null) return [];

    final parts = idToken.split('.');
    if (parts.length != 3) return [];

    final payload = utf8.decode(
      base64Url.decode(base64Url.normalize(parts[1])),
    );
    final claims = json.decode(payload) as Map<String, dynamic>;
    final rolesClaim = claims['roles'];
    if (rolesClaim is List) {
      return rolesClaim.cast<String>();
    }
    return [];
  }

  Future<bool> hasRole(String role) async {
    final r = await roles;
    return r.contains(role);
  }
}
