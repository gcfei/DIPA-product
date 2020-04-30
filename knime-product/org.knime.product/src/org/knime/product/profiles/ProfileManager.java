/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   31.01.2018 (thor): created
 */
package org.knime.product.profiles;

import static java.util.Collections.unmodifiableList;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.core.internal.preferences.DefaultPreferences;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.core.node.NodeLogger;
import org.knime.core.util.KNIMEServerHostnameVerifier;
import org.knime.core.util.PathUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Manager for profiles that should be applied during startup. This includes custom default preferences and
 * supplementary files such as database drivers. The profiles must be applied as early as possible during startup,
 * ideally as the first command in the application's start method.
 *
 * @author Thorsten Meinl, KNIME AG, Zurich, Switzerland
 */
public class ProfileManager {
    private static final ProfileManager INSTANCE = new ProfileManager();

    private static final String PROFILES_FOLDER = "profiles"; //$NON-NLS-1$

    private final List<Runnable> m_collectedLogs = new ArrayList<>(2);

    /**
     * Returns the singleton instance.
     *
     * @return the singleton, never <code>null</code>
     */
    public static ProfileManager getInstance() {
        return INSTANCE;
    }

    private final IProfileProvider m_provider;

    private ProfileManager() {
        List<Supplier<IProfileProvider>> potentialProviders = Arrays.asList(
            () -> new CommandlineProfileProvider(),
            () -> new WorkspaceProfileProvider(),
            getExtensionPointProviderSupplier());

        m_provider = potentialProviders.stream().map(s -> s.get())
                .filter(p -> !p.getRequestedProfiles().isEmpty())
                .findFirst().orElse(new EmptyProfileProvider());
    }

    private Supplier<IProfileProvider> getExtensionPointProviderSupplier() {
        return () -> {
            IExtensionRegistry registry = Platform.getExtensionRegistry();
            IExtensionPoint point = registry.getExtensionPoint("org.knime.product.profileProvider"); //$NON-NLS-1$

            Optional<IConfigurationElement> extension =
                    Stream.of(point.getExtensions()).flatMap(ext -> Stream.of(ext.getConfigurationElements())).findFirst();

            IProfileProvider provider = new EmptyProfileProvider();
            if (extension.isPresent()) {
                try {
                    provider = (IProfileProvider)extension.get().createExecutableExtension("class"); //$NON-NLS-1$
                } catch (CoreException ex) {
                    m_collectedLogs.add(() -> NodeLogger.getLogger(ProfileManager.class).error(
                        Messages.ProfileManager_3
                            + extension.get().getAttribute("class") + Messages.ProfileManager_5, //$NON-NLS-1$
                        ex));
                }
            }
            return provider;
        };
    }

    /**
     * Apply the available profiles to this instance. This includes setting new default preferences and copying
     * supplementary files to instance's configuration area.
     */
    public void applyProfiles() {
        List<Path> localProfiles = fetchProfileContents();
        try {
            applyPreferences(localProfiles);
        } catch (IOException ex) {
            m_collectedLogs.add(() -> NodeLogger.getLogger(ProfileManager.class)
                .error(Messages.ProfileManager_6 + ex.getMessage(), ex));
        }

        m_collectedLogs.stream().forEach(r -> r.run());
    }


    @SuppressWarnings("restriction")
    private void applyPreferences(final List<Path> profiles) throws IOException {
        if (DefaultPreferences.pluginCustomizationFile != null) {
            return; // plugin customizations are already explicitly provided by someone else
        }

        Properties combinedProperties = new Properties();
        for (Path dir : profiles) {
            List<Path> prefFiles = Files.walk(dir)
                    .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".epf")) //$NON-NLS-1$
                    .sorted()
                    .collect(Collectors.toList());

            Properties props = new Properties();
            for (Path f : prefFiles) {
                try (Reader r = Files.newBufferedReader(f, Charset.forName("UTF-8"))) { //$NON-NLS-1$
                    props.load(r);
                }
            }
            replaceVariables(props, dir);
            combinedProperties.putAll(props);
        }

        // remove "/instance" prefixes from preferences because otherwise they are not applied as default preferences
        // (because they are instance preferences...)
        for (Object key : new HashSet<>(combinedProperties.keySet())) {
            if (key.toString().startsWith("/instance/")) { //$NON-NLS-1$
                Object value = combinedProperties.remove(key);
                combinedProperties.put(key.toString().substring("/instance/".length()), value); //$NON-NLS-1$
            }
        }

        Path pluginCustFile = getStateLocation().resolve("combined-preferences.epf"); //$NON-NLS-1$
        if (Files.exists(pluginCustFile) && !Files.isWritable(pluginCustFile)) {
            Path tempCustFile = PathUtils.createTempFile("combined-preferences", ".epf"); //$NON-NLS-1$ //$NON-NLS-2$
            Path nonWorkingFile = pluginCustFile;
            pluginCustFile = tempCustFile;

            m_collectedLogs.add(() -> NodeLogger.getLogger(ProfileManager.class)
                .warn(Messages.ProfileManager_14 + nonWorkingFile + Messages.ProfileManager_15
                    + tempCustFile + Messages.ProfileManager_16));
        }

        // It's important here to write to a stream and not a reader because when reading the file back in
        // org.eclipse.core.internal.preferences.DefaultPreferences.loadProperties(String) also reads from a stream
        // and therefore assumes it's ISO-8859-1 encoded (with replacement for UTF characters).
        try (OutputStream out = Files.newOutputStream(pluginCustFile)) {
            combinedProperties.store(out, ""); //$NON-NLS-1$
        }
        DefaultPreferences.pluginCustomizationFile = pluginCustFile.toAbsolutePath().toString();
    }

    private void replaceVariables(final Properties props, final Path profileLocation) throws IOException {
        List<VariableReplacer> replacers = Arrays.asList(
            new VariableReplacer.EnvVariableReplacer(m_collectedLogs),
            new VariableReplacer.SyspropVariableReplacer(m_collectedLogs),
            new VariableReplacer.ProfileVariableReplacer(profileLocation, m_collectedLogs),
            new VariableReplacer.OriginVariableReplacer(profileLocation.getParent().resolve(".originHeaders"), //$NON-NLS-1$
                m_collectedLogs),
            new VariableReplacer.CustomVariableReplacer(m_provider, m_collectedLogs));

        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);

            for (VariableReplacer rep : replacers) {
                value = rep.replaceVariables(value);
            }

            // finally replace escaped "variables" so that the double dollars are removed, e.g.:
            //     /instance/org.knime.product/non-variable=bla/$${custom:var}/foo
            // becomes
            //     /instance/org.knime.product/non-variable=bla/${custom:var}/foo
            props.replace(key, value.replaceAll("\\$(\\$\\{[^:\\}]+:[^\\}]+\\})", "$1")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }


    private List<Path> fetchProfileContents() {
        List<String> profiles = m_provider.getRequestedProfiles();
        if (profiles.isEmpty()) {
            return Collections.emptyList();
        }

        URI profileLocation = m_provider.getProfilesLocation();
        Path localProfileLocation;
        if (isLocalProfile(profileLocation)) {
            localProfileLocation = Paths.get(profileLocation);
        } else if (isRemoteProfile(profileLocation)) {
            localProfileLocation = downloadProfiles(profileLocation);
        } else {
            throw new IllegalArgumentException(Messages.ProfileManager_21 + profileLocation.getScheme() + Messages.ProfileManager_22);
        }

        return profiles.stream().map(p -> localProfileLocation.resolve(p).normalize())
                .filter(p -> Files.isDirectory(p))
                // remove profiles that are outside the profile root (e.g. with "../" in their name)
                .filter(p -> p.startsWith(localProfileLocation))
                .collect(Collectors.toList());
    }

    private static boolean isLocalProfile(final URI profileLocation) {
        return "file".equalsIgnoreCase(profileLocation.getScheme()); //$NON-NLS-1$
    }

    private static boolean isRemoteProfile(final URI profileLocation) {
        return profileLocation.getScheme().startsWith("http"); //$NON-NLS-1$
    }

    private Path getStateLocation() {
        Bundle myself = FrameworkUtil.getBundle(getClass());
        return Platform.getStateLocation(myself).toFile().toPath();
    }

    private Path downloadProfiles(final URI profileLocation) {
        Path stateDir = getStateLocation();
        Path profileDir = stateDir.resolve(PROFILES_FOLDER);

        try {
            // compute list of profiles that are requested but not present locally yet
            List<String> newRequestedProfiles = new ArrayList<>(m_provider.getRequestedProfiles());
            if (Files.isDirectory(profileDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDir, p -> Files.isDirectory(p))) {
                    stream.forEach(p -> newRequestedProfiles.remove(p.getFileName().toString()));
                }
            }

            Files.createDirectories(stateDir);

            URIBuilder builder = new URIBuilder(profileLocation);
            builder.addParameter("profiles", String.join(",", m_provider.getRequestedProfiles())); //$NON-NLS-1$ //$NON-NLS-2$
            URI profileUri = builder.build();

            m_collectedLogs
                .add(() -> NodeLogger.getLogger(ProfileManager.class).info(Messages.ProfileManager_27 + profileUri));

            // proxies
            HttpHost proxy = ProxySelector.getDefault().select(profileUri).stream()
                    .filter(p -> p.address() != null)
                    .findFirst()
                    .map(p -> new HttpHost(((InetSocketAddress) p.address()).getAddress()))
                    .orElse(null);

            // timeout; we cannot access KNIMEConstants here because that would access preferences
            int timeout = 2000;
            String to = System.getProperty("knime.url.timeout", Integer.toString(timeout)); //$NON-NLS-1$
            try {
                timeout = Integer.parseInt(to);
            } catch (NumberFormatException ex) {
                m_collectedLogs.add(() -> NodeLogger.getLogger(ProfileManager.class)
                    .warn(Messages.ProfileManager_29 + to, ex));
            }
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(timeout)
                    .setProxy(proxy)
                    .setConnectionRequestTimeout(timeout)
                    .build();


            try (CloseableHttpClient client = HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .setSSLHostnameVerifier(KNIMEServerHostnameVerifier.getInstance())
                    .setRedirectStrategy(new DefaultRedirectStrategy()).build()) {
                HttpGet get = new HttpGet(profileUri);

                if (newRequestedProfiles.isEmpty() && Files.isDirectory(profileDir)) {
                    // if new profiles are requested we must not make a conditional request
                    Instant lastModified = Files.getLastModifiedTime(profileDir).toInstant();
                    get.setHeader("If-Modified-Since", //$NON-NLS-1$
                        DateTimeFormatter.RFC_1123_DATE_TIME.format(lastModified.atZone(ZoneId.of("GMT")))); //$NON-NLS-1$
                }

                try (CloseableHttpResponse response = client.execute(get)) {
                    int code = response.getStatusLine().getStatusCode();
                    if ((code >= 200) && (code < 300)) {
                        Header ct = response.getFirstHeader("Content-Type"); //$NON-NLS-1$
                        if ((ct == null) || (ct.getValue() == null) || !ct.getValue().startsWith("application/zip")) { //$NON-NLS-1$
                            // this is a workaround because ZipInputStream doesn't complain when the read contents are
                            // no zip file - it just processes an empty zip
                            throw new IOException(Messages.ProfileManager_34);
                        }

                        Path tempFile = PathUtils.createTempFile("profile-download", ".zip"); //$NON-NLS-1$ //$NON-NLS-2$
                        try (OutputStream os = Files.newOutputStream(tempFile)) {
                            IOUtils.copyLarge(response.getEntity().getContent(), os);
                        }

                        Path tempDir = PathUtils.createTempDir("profile-download", stateDir); //$NON-NLS-1$
                        try (ZipFile zf = new ZipFile(tempFile.toFile())) {
                            PathUtils.unzip(zf, tempDir);
                        }

                        // replace profiles only if new data has been downloaded successfully
                        PathUtils.deleteDirectoryIfExists(profileDir);
                        Files.move(tempDir, profileDir, StandardCopyOption.ATOMIC_MOVE);
                        Files.delete(tempFile);

                        writeOriginHeaders(response.getAllHeaders(), profileDir);
                    } else if (code == 304) { // 304 = Not Modified
                        writeOriginHeaders(response.getAllHeaders(), profileDir);
                    } else {
                        HttpEntity body = response.getEntity();
                        String msg;
                        if (body.getContentType().getValue().startsWith("text/")) { //$NON-NLS-1$
                            byte[] buf = new byte[Math.min(4096, Math.max(4096, (int)body.getContentLength()))];
                            int read = body.getContent().read(buf);
                            msg = new String(buf, 0, read, "US-ASCII").trim(); //$NON-NLS-1$
                        } else if (!response.getStatusLine().getReasonPhrase().isEmpty()) {
                            msg = response.getStatusLine().getReasonPhrase();
                        } else {
                            msg = Messages.ProfileManager_40 + response.getStatusLine().getStatusCode();
                        }

                        throw new IOException(msg);
                    }
                }
            }
        } catch (IOException | URISyntaxException ex) {
            String msg = Messages.ProfileManager_41 + profileLocation + ": " + ex.getMessage() + ". " //$NON-NLS-2$ //$NON-NLS-3$
                + (Files.isDirectory(profileDir) ? Messages.ProfileManager_44
                    : Messages.ProfileManager_45);
            m_collectedLogs.add(() -> NodeLogger.getLogger(ProfileManager.class).error(msg, ex));
        }

        return profileDir;
    }

    private static void writeOriginHeaders(final Header[] allHeaders, final Path profileDir) throws IOException {
        Path originHeadersCache = profileDir.resolve(".originHeaders"); //$NON-NLS-1$
        Properties props = new Properties();
        for (Header h : allHeaders) {
            props.put(h.getName(), h.getValue());
        }
        try (OutputStream os = Files.newOutputStream(originHeadersCache)) {
            props.store(os, ""); //$NON-NLS-1$
        }
    }

    /**
     * The path to the local profiles directory (either a local profiles folder or download and 'cached' profiles from a
     * remote location).
     *
     * @return the local profiles directory or an empty optional if no profiles available
     */
    public Optional<Path> getLocalProfilesLocation() {
        URI profileLocation = m_provider.getProfilesLocation();
        if (profileLocation == null) {
            return Optional.empty();
        }
        if (isLocalProfile(profileLocation)) {
            return Optional.of(Paths.get(m_provider.getProfilesLocation()));
        } else if (isRemoteProfile(profileLocation)) {
            return Optional.of(getStateLocation().resolve(PROFILES_FOLDER));
        } else {
            throw new IllegalArgumentException(Messages.ProfileManager_48 + profileLocation.getScheme() + Messages.ProfileManager_49);
        }
    }

    /**
     * Returns the list of requested profiles. See also {@link IProfileProvider#getRequestedProfiles()}.
     *
     * @return the list, never <code>null</code> but can be empty
     */
    public List<String> getRequestProfiles() {
        return unmodifiableList(m_provider.getRequestedProfiles());
    }
}
