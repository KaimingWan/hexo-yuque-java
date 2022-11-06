package com.kaimingwan.core.http;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.kaimingwan.core.http.model.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.util.TextUtils;

/**
 * http client based on okhttp. Will not throw runtime exception here, need to catch exception
 * outside.
 *
 * <pre>
 * [Attention]
 *  All returned json string are processed with jackson
 * </pre>
 *
 * @author wanshao
 * @ref get examples from https://square.github.io/okhttp/
 **/
@Slf4j
public class ConsoleHttpClient {

  public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private static final int CONNECTION_POOL_SIZE = 500;

  private static final long KEEP_ALIVE_TIME_MILLIS = 60000;

  private static final long HTTP_DEFAULT_TIMEOUT_MS = 10000;

  private static OkHttpClient client = null;

  private static OkHttpClient httpsClient = null;

  public static ResponseData post(String url, String json, Map<String, String> headerMap) {
    Response response = null;
    try {
      RequestBody body = RequestBody.create(JSON, json);
      Request request;
      if (headerMap != null && headerMap.size() > 0) {
        Headers headers = Headers.of(headerMap);
        // build with token, otherwise console reject the request
        request = new Request.Builder().headers(headers).url(url).post(body).build();
      } else {
        request = new Request.Builder().url(url).post(body).build();
      }
      Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        response = executeRequest(url, request);
      } catch (SocketTimeoutException exception) {
        long elapsedTimeMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
        if (elapsedTimeMs < 300) {
          log.info("Return socket exception immediately. Url is " + url + " and header is "
              + new ObjectMapper().writeValueAsString(headerMap) + ", json param is " + json
              + ". Current connection count is:" + client.connectionPool().connectionCount()
              + ", idle connection count is:" + client.connectionPool().idleConnectionCount()
              + ". ");
        }
        throw exception;
      }
      if (response.code() == 200) {
        ResponseData successData = new ObjectMapper().readValue(response.body().string(),
            ResponseData.class);
        if (successData.isSuccess()) {
          successData.setCode(ResultEnum.SUCCESS.getCode());
        }
        return successData;
      } else {
        String errorMsg =
            "invoke http post failed and http status " + response.code() + " with url [" + url
                + "].";
        log.error(errorMsg);
        return ResponseDataUtil.buildError(ResultEnum.ERROR.getCode(), "error",
            response.body().string());
      }
    } catch (Throwable e) {
      String msg = "invoke http post failed with url [" + url + "].msg: "
          + ExceptionUtils.getRootCauseMessage(e);
      log.error(msg, e);
      return ResponseDataUtil.buildError(msg);
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }

  public static ResponseData getWithJsonDeserialization(String url) {
    ResponseData rs = getWithResponseData(url, new HashMap<String, String>());
    return jsonDeserialization(rs);
  }

  public static ResponseData getWithJsonDeserialization(String url, Map<String, String> headers) {
    ResponseData rs = getWithResponseData(url, headers);
    return jsonDeserialization(rs);
  }

  protected static ResponseData jsonDeserialization(ResponseData rs) {
    if (rs.isSuccess()) {
      try {
        return new ObjectMapper().readValue((String) rs.getData(), ResponseData.class);
      } catch (JsonProcessingException e) {
        String msg = "json process exception,msg:" + ExceptionUtils.getRootCauseMessage(e);
        log.error(msg, e);
        return ResponseDataUtil.buildError(msg);
      }
    } else {
      return rs;
    }
  }

  public static ResponseData getWithResponseData(String url, Map<String, String> headers) {
    Response response = null;
    try {
      response = get(url, headers);
      if (response.code() == 200) {
        return ResponseDataUtil.buildSuccess(response.body().string());
      } else {
        throw new RuntimeException(
            "execute http get fail(http code:" + response.code() + ").url:" + url);
      }
    } catch (IOException e) {
      String errorMsg =
          "handle result (" + url + ") error,msg:" + ExceptionUtils.getRootCauseMessage(e);
      log.error(errorMsg, e);
      throw new RuntimeException(errorMsg, e);
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }

  public static ResponseData getImageFile(String url, Map<String, String> headers) {
    Response response = null;
    try {
      response = get(url, headers);
      if (response.code() == 200) {
        return ResponseDataUtil.buildSuccess("success", response.body().bytes(),
            getHeaderFileName(response));
      } else {
        throw new RuntimeException(
            "execute http get fail(http code:" + response.code() + ").url:" + url);
      }
    } catch (Exception e) {
      String errorMsg =
          "handle result (" + url + ") error,msg:" + ExceptionUtils.getRootCauseMessage(e);
      log.error(errorMsg, e);
      throw new RuntimeException(errorMsg, e);
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }

  private static String getHeaderFileName(Response response) {
    String dispositionHeader = response.header("Content-Disposition").toLowerCase();
    if (!TextUtils.isEmpty(dispositionHeader)) {
      String[] strings = dispositionHeader.split(";");
      if (strings.length > 1) {
        dispositionHeader = strings[1].replace("filename*=utf-8''", "");
        dispositionHeader = dispositionHeader.replace("\"", "");
        return dispositionHeader;
      }
      return "";
    }
    return "";
  }

  public static String getWithString(String url, Map<String, String> headers) {
    Response response = null;
    try {
      response = get(url, headers);
      if (response.code() == 200) {
        return response.body().string();
      } else {
        throw new RuntimeException(
            "execute http get fail(http code:" + response.code() + ").url:" + url);
      }
    } catch (IOException e) {
      String errorMsg =
          "handle result (" + url + ") failed,msg:" + ExceptionUtils.getRootCauseMessage(e);
      log.error(errorMsg, e);
      throw new RuntimeException(errorMsg, e);
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }

  public static byte[] getWithBytes(String url, Map<String, String> headers) {
    Response response = null;
    try {
      response = get(url, headers);
      if (response.code() == 200) {
        return response.body().bytes();
      } else {
        throw new RuntimeException(
            "execute http get fail(http code:" + response.code() + ").url:" + url);
      }
    } catch (IOException e) {
      String errorMsg =
          "handle result (" + url + ") failed,msg:" + ExceptionUtils.getRootCauseMessage(e);
      log.error(errorMsg, e);
      throw new RuntimeException(errorMsg, e);
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }

  public static Response getWithBasicAuth(String url, Map<String, String> headers, String userName,
      String password) {
    String credential = Credentials.basic(userName, password, Charsets.UTF_8);
    headers.put("Authorization", credential);
    return get(url, headers);
  }

  protected static Response get(String url, Map<String, String> headers) {
    try {
      Request.Builder builder = new Request.Builder();
      if (headers != null && headers.size() > 0) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
          builder.header(header.getKey(), header.getValue());
        }
      }

      Request request = builder.url(url).build();
      return executeRequest(url, request);
    } catch (Throwable e) {
      String msg =
          "invoke http get failed with url [" + url + "].msg:" + ExceptionUtils.getRootCauseMessage(
              e);
      log.error(msg, e);
      throw new RuntimeException(msg, e);
    }
  }

  protected static Response executeRequest(String url, Request request) throws Exception {
    if (url.toLowerCase().startsWith("https")) {
      if (httpsClient == null) {
        X509TrustManager trustManager = getTrustManager();
        SSLSocketFactory socketFactory = getSSLSocketFactory(trustManager);
        httpsClient = new OkHttpClient().newBuilder().sslSocketFactory(socketFactory, trustManager)
            .connectTimeout(HTTP_DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS).connectionPool(
                new ConnectionPool(CONNECTION_POOL_SIZE, KEEP_ALIVE_TIME_MILLIS,
                    TimeUnit.MILLISECONDS))
            .writeTimeout(HTTP_DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
      }

      return httpsClient.newCall(request).execute();
    } else {
      if (client == null) {
        client = new OkHttpClient().newBuilder()
            .connectTimeout(HTTP_DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS).connectionPool(
                new ConnectionPool(CONNECTION_POOL_SIZE, KEEP_ALIVE_TIME_MILLIS,
                    TimeUnit.MILLISECONDS))
            .writeTimeout(HTTP_DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();
      }

      return client.newCall(request).execute();
    }
  }

  protected static SSLSocketFactory getSSLSocketFactory(TrustManager trustManager)
      throws NoSuchAlgorithmException, KeyManagementException {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[]{trustManager}, null);
    return context.getSocketFactory();
  }

  protected static X509TrustManager getTrustManager() {
    return new X509TrustManager() {

      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {

      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {

      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }
}
