/*
This file is part of Tek271 Reverse Proxy Server.

Tek271 Reverse Proxy Server is free software: you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Tek271 Reverse Proxy Server is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License
along with Tek271 Reverse Proxy Server.  If not, see http://www.gnu.org/licenses/
 */
package com.tek271.reverseProxy.servlet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.tek271.reverseProxy.model.Mapping;
import com.tek271.reverseProxy.text.UrlMapper;
import com.tek271.reverseProxy.utils.HttpDeleteWithBody;
import com.tek271.reverseProxy.utils.Tuple2;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpParams;

public class ProxyFilter implements Filter {
    private static final String APPLICATION_JSON = "application/json";
    private static final String STRING_CONTENT_LENGTH_HEADER_NAME = "Content-Length";
    private static final String STRING_HOST_HEADER_NAME = "Host";


    public void init(FilterConfig filterConfig) throws ServletException {
    }

    public void destroy() {
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!isHttp(request, response)) {
            return;
        }

        Tuple2<Mapping, String> mapped = mapUrlProxyToHidden(request);
        if (mapped.isNull()) {
            chain.doFilter(request, response);
            return;
        }

        executeRequest(response, mapped.e1, mapped.e2, ((HttpServletRequest) request));
    }

    private static boolean isHttp(ServletRequest request, ServletResponse response) {
        return (request instanceof HttpServletRequest) && (response instanceof HttpServletResponse);
    }

    private static Tuple2<Mapping, String> mapUrlProxyToHidden(ServletRequest request) {
        String oldUrl = ((HttpServletRequest) request).getRequestURL().toString();
        String queryString = ((HttpServletRequest) request).getQueryString();
        if (queryString != null) {
            oldUrl += "?" + queryString;
        }
        return UrlMapper.mapFullUrlProxyToHidden(oldUrl);
    }

    /**
     * Helper method for passing post-requests
     */
    @SuppressWarnings({ "JavaDoc" })
    private static HttpUriRequest createNewRequest(HttpServletRequest request, String newUrl) throws IOException {
        String method = request.getMethod();
        if (method.equals("POST")) {
            HttpPost httppost = new HttpPost(newUrl);
            if (ServletFileUpload.isMultipartContent(request)) {
                MultipartEntity entity = getMultipartEntity(request);
                httppost.setEntity(entity);
                addCustomHeaders(request, httppost, "Content-Type");
            } else {
                StringEntity entity = getEntity(request);
                httppost.setEntity(entity);
                addCustomHeaders(request, httppost);
            }
            return httppost;
        } else if (method.equals("PUT")) {
            StringEntity entity = getEntity(request);
            HttpPut httpPut = new HttpPut(newUrl);
            httpPut.setEntity(entity);
            addCustomHeaders(request, httpPut);
            return httpPut;
        } else if (method.equals("DELETE")) {
            StringEntity entity = getEntity(request);
            HttpDeleteWithBody httpDelete = new HttpDeleteWithBody(newUrl);
            httpDelete.setEntity(entity);
            addCustomHeaders(request, httpDelete);
            return httpDelete;
        } else {
            HttpGet httpGet = new HttpGet(newUrl);
            addCustomGetHeaders(request, httpGet);
            return httpGet;
        }
    }

    private static void addCustomGetHeaders(HttpServletRequest request, HttpGet httpGet) {
        httpGet.addHeader("preferred-role", request.getHeader("preferred-role"));
        httpGet.addHeader("X-Source", request.getHeader("X-Source"));
        Enumeration headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = (String) headerNames.nextElement();
            Enumeration enumerationOfHeaderValues = request.getHeaders(headerName);
            while (enumerationOfHeaderValues.hasMoreElements()) {
                String headerValue = (String) enumerationOfHeaderValues.nextElement();
                if (headerName.equalsIgnoreCase("accept")) {
                    httpGet.setHeader(headerName, headerValue);
                }
            }


        }
    }

    private static MultipartEntity getMultipartEntity(HttpServletRequest request) {
        MultipartEntity entity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE);
        Enumeration<String> en = request.getParameterNames();
        while (en.hasMoreElements()) {
            String name = en.nextElement();
            String value = request.getParameter(name);
            try {
                if (name.equals("file")) {
                    FileItemFactory factory = new DiskFileItemFactory();
                    ServletFileUpload upload = new ServletFileUpload(factory);
                    upload.setSizeMax(10000000);// 10 Mo
                    List items = upload.parseRequest(request);
                    Iterator itr = items.iterator();
                    while (itr.hasNext()) {
                        FileItem item = (FileItem) itr.next();
                        File file = new File(item.getName());
                        FileOutputStream fos = new FileOutputStream(file);
                        fos.write(item.get());
                        fos.flush();
                        fos.close();
                        entity.addPart(name, new FileBody(file, "application/zip"));
                    }
                } else {
                    entity.addPart(name, new StringBody(value.toString(), "text/plain", Charset.forName("UTF-8")));
                }
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            } catch (FileUploadException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            } catch (FileNotFoundException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            } catch (IOException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }

        return entity;

    }

    @SuppressWarnings({ "unchecked" })
    private static void addCustomHeaders(HttpServletRequest original, HttpEntityEnclosingRequest request, String... skipElements) {
        Enumeration<String> en = original.getHeaderNames();
        while (en.hasMoreElements()) {
            String name = en.nextElement();
            if (contains(skipElements, name)) {
                continue;
            } else if ("X-HTTP-Method-Override".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            } else if ("X-Source".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            } else if ("preferred-role".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            } else if ("X-File-Name".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            } else if ("X-Requested-With".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            } else if ("X-Requested-By".equals(name)) {
                request.setHeader(name, original.getHeader(name));
            } else if ("Content-type".equalsIgnoreCase(name)) {
                request.setHeader(name, original.getHeader(name));
            }

        }
    }

    private static boolean contains(String[] skipElements, String name) {
        if (skipElements == null || skipElements.length == 0) {
            return false;
        }
        for (String skipElement : skipElements) {
            if (skipElement.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;

    }

    @SuppressWarnings({ "unchecked" })
    private static StringEntity getEntity(HttpServletRequest request)
            throws IOException {
        if (APPLICATION_JSON.equalsIgnoreCase(request.getHeader("Content-type"))) {
            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader bufferedReader = null;
            InputStream inputStream = request.getInputStream();
            if (inputStream != null) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                char[] charBuffer = new char[512];
                int bytesRead = -1;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    stringBuilder.append(charBuffer, 0, bytesRead);
                }
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            } else {
                stringBuilder.append("");
            }
            return new StringEntity(stringBuilder.toString(), "UTF-8");

        }
        List<NameValuePair> formparams = new ArrayList<NameValuePair>();
        Enumeration<String> en = request.getParameterNames();
        while (en.hasMoreElements()) {
            String name = en.nextElement();
            String value = request.getParameter(name);
            formparams.add(new BasicNameValuePair(name, value));
        }
        return new UrlEncodedFormEntity(formparams, "UTF-8");
    }

    private static void executeRequest(ServletResponse response, Mapping mapping, String newUrl, HttpServletRequest request)
            throws IOException {
        HttpClient httpclient = new DefaultHttpClient();
        HttpUriRequest httpRequest = createNewRequest(request, newUrl);

        HttpResponse r = httpclient.execute(httpRequest);
        HttpEntity entity = r.getEntity();

        ContentTranslator contentTranslator = new ContentTranslator(mapping, newUrl);
        contentTranslator.updateHeaders(r, response);
        contentTranslator.translate(r, entity, response);
    }

    private static void printHeaders(HttpUriRequest s) {
        Header[] en = s.getAllHeaders();
        for (Header header : en) {
            System.out.println("----" + header.getName() + ": " + header.getValue());
        }
    }

    private static void printParams(HttpUriRequest request) {
        HttpParams params = request.getParams();
        System.out.println(params);

    }

}
