class AuthConfig {
  static const issuer = 'http://curity.{{namespace}}.bluetext.lvh.me:8888/oauth/v2/oauth-anonymous';
  static const authorizationEndpoint =
      'http://curity.{{namespace}}.bluetext.lvh.me:8888/oauth/v2/oauth-authorize';
  static const tokenEndpoint =
      'http://curity.{{namespace}}.bluetext.lvh.me:8888/oauth/v2/oauth-token';
  static const clientId = 'flutter-app';
  static const redirectUri = 'com.bluetext.app://callback';
  static const scopes = ['openid', 'profile', 'email', 'roles'];
  static const apiBaseUrl = 'http://kong.{{namespace}}.bluetext.lvh.me:8888';
}
