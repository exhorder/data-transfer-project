/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dataportabilityproject.webapp;

import static org.apache.axis.transport.http.HTTPConstants.HEADER_HOST;
import static org.apache.axis.transport.http.HTTPConstants.HEADER_LOCATION;

import com.google.api.client.auth.oauth2.AuthorizationCodeResponseUrl;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.HttpCookie;
import java.util.Map;
import org.dataportabilityproject.PortabilityFlags;
import org.dataportabilityproject.ServiceProviderRegistry;
import org.dataportabilityproject.job.JobDao;
import org.dataportabilityproject.job.PortabilityJob;
import org.dataportabilityproject.shared.LogUtils;
import org.dataportabilityproject.shared.PortableDataType;
import org.dataportabilityproject.shared.auth.AuthData;
import org.dataportabilityproject.shared.auth.OnlineAuthDataGenerator;

/**
 * HttpHandler for callbacks from Oauth2 authorization flow.
 */
public class Oauth2CallbackHandler implements HttpHandler {

  private final ServiceProviderRegistry serviceProviderRegistry;
  private final JobDao jobDao;
  private final CryptoHelper cryptoHelper;

  public Oauth2CallbackHandler(ServiceProviderRegistry serviceProviderRegistry,
      JobDao jobDao,
      CryptoHelper cryptoHelper) {
    this.serviceProviderRegistry = serviceProviderRegistry;
    this.jobDao = jobDao;
    this.cryptoHelper = cryptoHelper;
  }

  public void handle(HttpExchange exchange) throws IOException {
    Preconditions.checkArgument(
        PortabilityServerUtils.validateRequest(exchange, HttpMethods.GET, "/callback/.*"));
    Headers responseHeaders = exchange.getResponseHeaders();
    Headers requestHeaders = exchange.getRequestHeaders();

    String requestURL = PortabilityServerUtils
        .createURL(exchange.getProtocol(), requestHeaders.getFirst(HEADER_HOST),
            exchange.getRequestURI().toString());
    LogUtils.log("Oauth2CallbackHandler getURL: %s", requestURL);

    AuthorizationCodeResponseUrl authResponse = new AuthorizationCodeResponseUrl(requestURL);

    // check for user-denied error
    if (authResponse.getError() != null) {
      LogUtils.log("Authorization DENIED: %s Redirecting to /error", authResponse.getError());
      responseHeaders.set(HEADER_LOCATION, "/error");
      exchange.sendResponseHeaders(303, -1);
      return;
    }

    LogUtils.log("Got valid authorization request");

    // retrieve cookie from exchange
    Map<String, HttpCookie> httpCookies = PortabilityServerUtils.getCookies(requestHeaders);
    HttpCookie encodedIdCookie = httpCookies.get(JsonKeys.ID_COOKIE_KEY);
    Preconditions
        .checkArgument(
            encodedIdCookie != null && !Strings.isNullOrEmpty(encodedIdCookie.getValue()),
            "Encoded Id cookie required");

    LogUtils.log("encodedIdCookie: %s", encodedIdCookie.getValue());
    String jobId = JobUtils.decodeId(encodedIdCookie.getValue());
    String state = JobUtils.decodeId(authResponse.getState());

    // TODO: Remove sanity check
    LogUtils.log("Checking state of job");
    Preconditions
        .checkState(state.equals(jobId), "Job id in cookie [%s] and request [%s] should match",
            jobId, state);

    LogUtils.log("Looking up the job by jobID");
    PortabilityJob job = PortabilityServerUtils.lookupJob(jobId, jobDao);
    PortableDataType dataType = JobUtils.getDataType(job.dataType());
    LogUtils.log("dataType: %s", dataType);

    // TODO: Determine import vs export mode
    // Hack! For now, if we don't have export auth data, assume it's for export.
    boolean isExport = (null == job.exportAuthData());

    // TODO: Determine service from job or from authUrl path?
    String service = isExport ? job.exportService() : job.importService();
    Preconditions.checkState(!Strings.isNullOrEmpty(service),
        "service not found, service: %s isExport: %b, jobId: %s", service, isExport, jobId);
    LogUtils.log("service: %s, isExport: %b", service, isExport);

    // Obtain the ServiceProvider from the registry
    OnlineAuthDataGenerator generator = serviceProviderRegistry.getOnlineAuth(service, dataType);

    // Retrieve initial auth data, if it existed
    AuthData initialAuthData = JobUtils.getInitialAuthData(job, isExport);

    // Generate and store auth data
    AuthData authData = generator
        .generateAuthData(authResponse.getCode(), jobId, initialAuthData, null);
    Preconditions.checkNotNull(authData, "Auth data should not be null");

    // Update the job
    // TODO: Remove persistence of auth data in storage at this point. The data will be passed
    // thru to the client via the cookie.
    PortabilityJob updatedJob = JobUtils.setAuthData(job, authData, isExport);
    jobDao.updateJob(updatedJob);

    // Set new cookie
    cryptoHelper.encryptAndSetCookie(responseHeaders, isExport, authData);

    String redirect = PortabilityFlags.baseUrl() + (isExport ? "/next" : "/copy");
    LogUtils.log("Redirecting to %s", redirect);
    responseHeaders.set(HEADER_LOCATION, redirect);
    exchange.sendResponseHeaders(303, -1);
  }
}