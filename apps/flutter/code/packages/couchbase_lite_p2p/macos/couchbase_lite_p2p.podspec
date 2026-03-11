Pod::Spec.new do |s|
  s.name             = 'couchbase_lite_p2p'
  s.version          = '0.0.2'
  s.summary          = 'Couchbase Lite P2P Flutter plugin.'
  s.description      = <<-DESC
Couchbase Lite P2P Flutter plugin for macOS.
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'FlutterMacOS'
  s.platform = :osx, '12.0'
  s.osx.deployment_target = '12.0'

  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.swift_version = '5.0'

  s.dependency 'CouchbaseLite-Swift-Enterprise', '~> 3.3'
end
