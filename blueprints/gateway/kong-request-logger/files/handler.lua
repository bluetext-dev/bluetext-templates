local RequestLoggerHandler = {
  PRIORITY = 1000,
  VERSION = "0.1.0",
}

function RequestLoggerHandler:access(conf)
  kong.log.notice("[request-logger] intercepted request: ",
    kong.request.get_method(), " ", kong.request.get_path())
end

return RequestLoggerHandler
