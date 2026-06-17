// Unit tests for the OIDC sample app's ApiClient.
//
// These guard the contract that historically broke the live RBAC E2E:
// every HTTP status code (success, denial, server-error) MUST come back
// as data on the http.Response, never as a thrown exception. The widget
// layer (HomePage._callApi) catches genuine exceptions (timeouts, socket
// errors) but leans on this contract to render 4xx/5xx without crashing.
//
// Run inside the Flutter dev pod (no emulator needed):
//   kubectl -n <ns> exec deploy/flutter -- sh -c 'cd /app && flutter test'
//
// Or locally if Flutter is on the host:
//   (cd code/services/<app_id> && flutter test)

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:workspace/services/api_client.dart';

void main() {
  group('ApiClient HTTP contract', () {
    test('403 response is returned as data (no exception)', () async {
      final mock = MockClient(
        (req) async => http.Response('{"error":"forbidden"}', 403),
      );
      final api = ApiClient(() async => 'tok', client: mock);

      final response = await api.get('/api/admin/users');

      expect(response.statusCode, 403);
      expect(response.body, contains('forbidden'));
    });

    test('200 response is returned as data', () async {
      final mock = MockClient(
        (req) async => http.Response('{"ok":true}', 200),
      );
      final api = ApiClient(() async => 'tok', client: mock);

      final response = await api.get('/api/hello');

      expect(response.statusCode, 200);
    });

    test('500 response is returned as data, not thrown', () async {
      final mock = MockClient(
        (req) async => http.Response('Internal Server Error', 500),
      );
      final api = ApiClient(() async => 'tok', client: mock);

      final response = await api.get('/api/broken');

      expect(response.statusCode, 500);
    });

    test('socket-level errors propagate as ClientException', () async {
      final mock = MockClient(
        (req) async => throw http.ClientException('Network unreachable'),
      );
      final api = ApiClient(() async => 'tok', client: mock);

      expect(api.get('/api/anywhere'), throwsA(isA<http.ClientException>()));
    });

    test('attaches Bearer token when token getter returns a value', () async {
      String? capturedAuth;
      final mock = MockClient((req) async {
        capturedAuth = req.headers['Authorization'];
        return http.Response('ok', 200);
      });
      final api = ApiClient(() async => 'my-token-abc', client: mock);

      await api.get('/api/hello');

      expect(capturedAuth, 'Bearer my-token-abc');
    });

    test('omits Authorization header when token getter returns null', () async {
      String? capturedAuth;
      final mock = MockClient((req) async {
        capturedAuth = req.headers['Authorization'];
        return http.Response('ok', 200);
      });
      final api = ApiClient(() async => null, client: mock);

      await api.get('/api/hello');

      expect(capturedAuth, isNull);
    });

    test('post() encodes the body as JSON', () async {
      String? capturedBody;
      String? capturedContentType;
      final mock = MockClient((req) async {
        capturedBody = req.body;
        capturedContentType = req.headers['Content-Type'];
        return http.Response('{}', 200);
      });
      final api = ApiClient(() async => 'tok', client: mock);

      await api.post('/api/items', {'name': 'thing', 'count': 3});

      expect(capturedContentType, 'application/json');
      expect(capturedBody, '{"name":"thing","count":3}');
    });

    test(
      'request that hangs past the 30s timeout throws TimeoutException',
      () async {
        // Defer past the ApiClient's hard timeout to prove the .timeout()
        // wrapper actually fires. fake_async would run faster but it can't
        // wrap the Completer-based MockClient here, so let real time tick
        // — overall test still under a minute.
        final mock = MockClient((req) async {
          await Future<void>.delayed(const Duration(seconds: 31));
          return http.Response('ok', 200);
        });
        final api = ApiClient(() async => 'tok', client: mock);

        await expectLater(
          api.get('/api/slow'),
          throwsA(isA<TimeoutException>()),
        );
      },
      timeout: const Timeout(Duration(seconds: 60)),
    );
  });
}
