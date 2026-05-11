// Copyright (C) 2026 GerritForge, Inc.
//
// Licensed under the BSL 1.1 (the "License");
// you may not use this file except in compliance with the License.
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.gerritforge.gerrit.plugins.ai.provider;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.Mockito.doReturn;

import com.gerritforge.gerrit.plugins.ai.provider.api.AiHttpClient;
import com.gerritforge.gerrit.plugins.ai.provider.api.AiHttpClientImpl;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AiHttpClientImplTest {

  @Mock CloseableHttpClient httpClientDelegate;

  @Mock HttpUriRequest httpClientRequest;

  @Mock CloseableHttpResponse httpResponse;

  @Mock StatusLine httpStatusLine;

  AiHttpClient aiHttpClient;

  @Before
  public void setup() throws IOException {
    doReturn(httpResponse).when(httpClientDelegate).execute(httpClientRequest);
    doReturn(httpStatusLine).when(httpResponse).getStatusLine();
    aiHttpClient = new AiHttpClientImpl(httpClientDelegate);
  }

  @Test
  public void shouldAcceptCallsReturningOk() throws IOException {
    doReturn(HttpServletResponse.SC_OK).when(httpStatusLine).getStatusCode();

    HttpResponse response =
        aiHttpClient.execute(
            httpClientRequest, (Integer status) -> status == HttpServletResponse.SC_OK, null);

    assertThat(response.getStatusLine().getStatusCode()).isEqualTo(HttpServletResponse.SC_OK);
  }

  @Test
  public void shouldThrowIOExceptionWhenReturningNotOk() throws IOException {
    doReturn(HttpServletResponse.SC_BAD_REQUEST).when(httpStatusLine).getStatusCode();

    assertThrows(
        IOException.class,
        () ->
            aiHttpClient.execute(
                httpClientRequest, (Integer status) -> status == HttpServletResponse.SC_OK, null));
  }

  @Test
  public void shouldFormatErrorFromBodyWhenReturningNotOk() throws IOException {
    String errorBody = "API error";
    doReturn(new StringEntity(errorBody, ContentType.TEXT_PLAIN)).when(httpResponse).getEntity();
    String expectedErrorPrefix = "This is the expected error : ";

    doReturn(HttpServletResponse.SC_BAD_REQUEST).when(httpStatusLine).getStatusCode();

    IOException exception =
        assertThrows(
            IOException.class,
            () ->
                aiHttpClient.execute(
                    httpClientRequest,
                    (Integer status) -> status == HttpServletResponse.SC_OK,
                    body -> expectedErrorPrefix + body));
    assertThat(exception.getMessage()).contains(errorBody);
    assertThat(exception.getMessage()).contains(expectedErrorPrefix);
  }
}
