class AuthConfig {
  static const issuer = 'http://curity.{{namespace}}.local.bluetext.io';
  static const authorizationEndpoint =
      'http://curity.{{namespace}}.local.bluetext.io/oauth/v2/oauth-authorize';
  static const tokenEndpoint =
      'http://curity.{{namespace}}.local.bluetext.io/oauth/v2/oauth-token';
  static const clientId = 'flutter-app';
  static const redirectUri = 'com.bluetext.app://callback';
  static const scopes = ['openid', 'profile', 'email', 'roles'];
  static const apiBaseUrl = 'http://kong.{{namespace}}.local.bluetext.io';
}
