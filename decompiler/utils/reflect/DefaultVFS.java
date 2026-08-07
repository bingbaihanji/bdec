package com.bingbaihanji.bdec.decompiler.utils.reflect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * 默认 VFS 实现,适用于大多数应用服务器(如 Tomcat,Jetty)
 *
 * @author bingbaihanji
 * @date 2026-06-10
 */
class DefaultVFS extends VFS {

//    private static final Logger log = LoggerFactory.getLogger(DefaultVFS.class);

    /** JAR 文件魔数(PK..) */
    private static final byte[] JAR_MAGIC = {'P', 'K', 3, 4};

    private static void closeQuietly(InputStream is) {
        if (is != null) {
            try {
                is.close();
            } catch (Exception e) {
//                if (log.isDebugEnabled()) {
//                    log.debug("关闭输入流异常", e);
//                }
            }
        }
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public List<String> list(URL url, String path) throws IOException {
        InputStream is = null;
        try {
            List<String> resources = new ArrayList<>();
            URL jarUrl = findJarForResource(url);
            if (jarUrl != null) {
                is = jarUrl.openStream();
//                if (log.isDebugEnabled()) {
//                    log.debug("列出 JAR: {}", url);
//                }
                resources = listResources(new JarInputStream(is), path);
            } else {
                List<String> children = listChildren(url, path);
                String prefix = url.toExternalForm();
                if (!prefix.endsWith("/")) {
                    prefix = prefix + "/";
                }
                for (String child : children) {
                    String resourcePath = path + "/" + child;
                    resources.add(resourcePath);
                    URL childUrl = new URL(prefix + child);
                    resources.addAll(list(childUrl, resourcePath));
                }
            }
            return resources;
        } finally {
            closeQuietly(is);
        }
    }

    private List<String> listChildren(URL url, String path) throws IOException {
        try {
            if (isJar(url)) {
                return listJarEntries(url);
            } else {
                return listDirectoryEntries(url, path);
            }
        } catch (FileNotFoundException e) {
            if ("file".equals(url.getProtocol())) {
                return listFileSystemDirectory(url);
            } else {
                throw e;
            }
        }
    }

    private List<String> listJarEntries(URL url) throws IOException {
        List<String> entries = new ArrayList<>();
        try (InputStream is = url.openStream(); JarInputStream jarInput = new JarInputStream(is)) {
//            if (log.isDebugEnabled()) {
//                log.debug("列出 JAR: {}", url);
//            }
            for (JarEntry entry; (entry = jarInput.getNextJarEntry()) != null; ) {
//                if (log.isDebugEnabled()) {
//                    log.debug("JAR 条目: {}", entry.getName());
//                }
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    private List<String> listDirectoryEntries(URL url, String path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream is = url.openStream(); BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            for (String line; (line = reader.readLine()) != null; ) {
//                if (log.isDebugEnabled()) {
//                    log.debug("读取条目: {}", line);
//                }
                lines.add(line);
                if (getResources(path + "/" + line).isEmpty()) {
                    lines.clear();
                    break;
                }
            }
        }
        if (!lines.isEmpty()) {
//            if (log.isDebugEnabled()) {
//                log.debug("列出目录: {}", url);
//            }
            return lines;
        }
        return listFileSystemDirectory(url);
    }

    private List<String> listFileSystemDirectory(URL url) {
        File file = new File(java.net.URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8));
//        if (log.isDebugEnabled()) {
//            log.debug("列出目录: {}", file.getAbsolutePath());
//        }
        if (file.isDirectory()) {
            String[] list = file.list();
            if (list != null) {
                return Arrays.asList(list);
            }
        }
        return Collections.emptyList();
    }

    protected List<String> listResources(JarInputStream jar, String path) throws IOException {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        List<String> resources = new ArrayList<>();
        for (JarEntry entry; (entry = jar.getNextJarEntry()) != null; ) {
            if (!entry.isDirectory()) {
                String name = entry.getName();
                if (!name.startsWith("/")) {
                    name = "/" + name;
                }
                if (name.startsWith(path)) {
//                    if (log.isDebugEnabled()) {
//                        log.debug("找到资源: {}", name);
//                    }
                    resources.add(name.substring(1));
                }
            }
        }
        return resources;
    }

    protected URL findJarForResource(URL url) throws MalformedURLException {
//        if (log.isDebugEnabled()) {
//            log.debug("查找 JAR URL: {}", url);
//        }
        while (true) {
            try {
                url = new URL(url.getFile());
//                if (log.isDebugEnabled()) {
//                    log.debug("内层 URL: {}", url);
//                }
            } catch (MalformedURLException e) {
                break;
            }
        }
        StringBuilder jarUrl = new StringBuilder(url.toExternalForm());
        int index = jarUrl.lastIndexOf(".jar");
        if (index < 0) {
//            if (log.isDebugEnabled()) {
//                log.debug("非 JAR: {}", jarUrl);
//            }
            return null;
        }
        jarUrl.setLength(index + 4);
//        if (log.isDebugEnabled()) {
//            log.debug("提取的 JAR URL: {}", jarUrl);
//        }
        try {
            URL testUrl = new URL(jarUrl.toString());
            if (isJar(testUrl)) {
                return testUrl;
            }
//            if (log.isDebugEnabled()) {
//                log.debug("非 JAR: {}", jarUrl);
//            }
            jarUrl.replace(0, jarUrl.length(), testUrl.getFile());
            File file = new File(jarUrl.toString());
            if (!file.exists()) {
                file = new File(URLEncoder.encode(jarUrl.toString(), StandardCharsets.UTF_8));
            }
            if (file.exists()) {
//                if (log.isDebugEnabled()) {
//                    log.debug("尝试实际文件: {}", file.getAbsolutePath());
//                }
                testUrl = file.toURI().toURL();
                if (isJar(testUrl)) {
                    return testUrl;
                }
            }
        } catch (MalformedURLException e) {
//            log.warn("无效的 JAR URL: {}", jarUrl);
        }
//        if (log.isDebugEnabled()) {
//            log.debug("非 JAR: {}", jarUrl);
//        }
        return null;
    }

    protected boolean isJar(URL url) {
        return isJar(url, new byte[JAR_MAGIC.length]);
    }

    protected boolean isJar(URL url, byte[] buffer) {
        InputStream is = null;
        try {
            is = url.openStream();
            is.read(buffer, 0, JAR_MAGIC.length);
            if (Arrays.equals(buffer, JAR_MAGIC)) {
//                if (log.isDebugEnabled()) {
//                    log.debug("发现 JAR: {}", url);
//                }
                return true;
            }
        } catch (Exception e) {
//            if (log.isDebugEnabled()) {
//                log.debug("判断 JAR 格式异常: {}", url, e);
//            }
        } finally {
            closeQuietly(is);
        }
        return false;
    }

}
