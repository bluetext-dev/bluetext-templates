import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:app_links/app_links.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:url_launcher/url_launcher.dart';

import '../config/auth_config.dart';

/// OIDC auth service using url_launcher + app_links.
///
/// Opens the authorization URL in an external browser (not Chrome Custom Tab)
/// so the Flutter process stays alive during the login flow. Persists PKCE
/// state in secure storage to survive Android process death.
class AuthService {
  final FlutterSecureStorage _storage = const FlutterSecureStorage();
  final AppLinks _appLinks = AppLinks();

  static const _accessTokenKey = 'access_token';
  static const _refreshTokenKey = 'refresh_token';
  static const _idTokenKey = 'id_token';
  static const _pkceVerifierKey = 'pkce_code_verifier';
  static const _pkceStateKey = 'pkce_state';

  /// Complete a login that was interrupted by process death.
  /// Call this on app startup before showing the UI.
  /// Returns true if a pending login was completed.
  Future<bool> completePendingLogin() async {
    final verifier = await _storage.read(key: _pkceVerifierKey);
    if (verifier == null) return false;

    final initialLink = await _appLinks.getInitialLink();
    if (initialLink == null || initialLink.scheme != 'com.bluetext.app') {
      // No callback waiting — clean up stale PKCE state
      await _cleanupPkce();
      return false;
    }

    return _handleCallback(initialLink);
  }

  /// Start the OIDC login flow.
  Future<bool> login() async {
    try {
      final verifier = _generateRandom(64);
      final challenge = base64Url
          .encode(sha256.convert(utf8.encode(verifier)).bytes)
          .replaceAll('=', '');
      final state = _generateRandom(22);
      final nonce = _generateRandom(22);

      // Persist PKCE params so they survive process death
      await _storage.write(key: _pkceVerifierKey, value: verifier);
      await _storage.write(key: _pkceStateKey, value: state);

      final callbackFuture = _appLinks.uriLinkStream
          .where((uri) => uri.scheme == 'com.bluetext.app')
          .first
          .timeout(const Duration(minutes: 5));

      final authUrl = Uri.parse(AuthConfig.authorizationEndpoint).replace(
        queryParameters: {
          'client_id': AuthConfig.clientId,
          'redirect_uri': AuthConfig.redirectUri,
          'response_type': 'code',
          'scope': AuthConfig.scopes.join(' '),
          'code_challenge': challenge,
          'code_challenge_method': 'S256',
          'state': state,
          'nonce': nonce,
        },
      );

      await launchUrl(authUrl, mode: LaunchMode.externalApplication);

      final callbackUri = await callbackFuture;
      return _handleCallback(callbackUri);
    } catch (e) {
      await _cleanupPkce();
      return false;
    }
  }

  Future<bool> _handleCallback(Uri callbackUri) async {
    try {
      final code = callbackUri.queryParameters['code'];
      final returnedState = callbackUri.queryParameters['state'];
      final storedState = await _storage.read(key: _pkceStateKey);
      final verifier = await _storage.read(key: _pkceVerifierKey);

      if (code == null || verifier == null || returnedState != storedState) {
        await _cleanupPkce();
        return false;
      }

      final response = await http.post(
        Uri.parse(AuthConfig.tokenEndpoint),
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: {
          'grant_type': 'authorization_code',
          'client_id': AuthConfig.clientId,
          'redirect_uri': AuthConfig.redirectUri,
          'code': code,
          'code_verifier': verifier,
        },
      );

      await _cleanupPkce();

      if (response.statusCode != 200) return false;

      final tokens = json.decode(response.body) as Map<String, dynamic>;
      await _storage.write(key: _accessTokenKey, value: tokens['access_token']);
      await _storage.write(
          key: _refreshTokenKey, value: tokens['refresh_token']);
      await _storage.write(key: _idTokenKey, value: tokens['id_token']);
      return true;
    } catch (e) {
      await _cleanupPkce();
      return false;
    }
  }

  Future<void> _cleanupPkce() async {
    await _storage.delete(key: _pkceVerifierKey);
    await _storage.delete(key: _pkceStateKey);
  }

  Future<void> logout() async {
    await _storage.delete(key: _accessTokenKey);
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _idTokenKey);
  }

  Future<String?> refreshToken() async {
    final stored = await _storage.read(key: _refreshTokenKey);
    if (stored == null) return null;

    final response = await http.post(
      Uri.parse(AuthConfig.tokenEndpoint),
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: {
        'grant_type': 'refresh_token',
        'client_id': AuthConfig.clientId,
        'refresh_token': stored,
      },
    );

    if (response.statusCode != 200) return null;

    final tokens = json.decode(response.body) as Map<String, dynamic>;
    await _storage.write(key: _accessTokenKey, value: tokens['access_token']);
    if (tokens['refresh_token'] != null) {
      await _storage.write(
          key: _refreshTokenKey, value: tokens['refresh_token']);
    }
    if (tokens['id_token'] != null) {
      await _storage.write(key: _idTokenKey, value: tokens['id_token']);
    }
    return tokens['access_token'];
  }

  Future<String?> get token async =>
      await _storage.read(key: _accessTokenKey);

  Future<bool> get isAuthenticated async =>
      await _storage.read(key: _accessTokenKey) != null;

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
    if (rolesClaim is List) return rolesClaim.cast<String>();
    return [];
  }

  Future<bool> hasRole(String role) async =>
      (await roles).contains(role);

  String _generateRandom(int length) {
    const chars =
        'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
    final rng = Random.secure();
    return List.generate(length, (_) => chars[rng.nextInt(chars.length)]).join();
  }
}
