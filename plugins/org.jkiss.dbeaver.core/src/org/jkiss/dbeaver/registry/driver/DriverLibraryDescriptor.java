/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2015 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.registry.driver;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDriverLibrary;
import org.jkiss.dbeaver.model.runtime.OSDescriptor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.registry.RegistryConstants;
import org.jkiss.dbeaver.registry.maven.MavenArtifact;
import org.jkiss.dbeaver.registry.maven.MavenArtifactReference;
import org.jkiss.dbeaver.registry.maven.MavenLocalVersion;
import org.jkiss.dbeaver.registry.maven.MavenRegistry;
import org.jkiss.dbeaver.runtime.RuntimeUtils;
import org.jkiss.dbeaver.ui.UIIcon;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;

/**
 * DriverLibraryDescriptor
 */
public class DriverLibraryDescriptor implements DBPDriverLibrary
{
    static final Log log = Log.getLog(DriverLibraryDescriptor.class);

    public static final String FILE_SOURCE_MAVEN = "maven:/";
    public static final String FILE_SOURCE_REPO = "repo:/";
    public static final String FILE_SOURCE_PLATFORM = "platform:/";
    //public static final String FILE_SOURCE_LOCAL = "file:/";

    private final DriverDescriptor driver;
    private final FileType type;
    private final OSDescriptor system;
    private String path;
    private String description;
    private boolean custom;
    private boolean disabled;

    public DriverLibraryDescriptor(DriverDescriptor driver, FileType type, String path)
    {
        this.driver = driver;
        this.type = type;
        this.system = DBeaverCore.getInstance().getLocalSystem();
        this.path = path;
        this.custom = true;
    }

    DriverLibraryDescriptor(DriverDescriptor driver, IConfigurationElement config)
    {
        this.driver = driver;
        this.type = FileType.valueOf(config.getAttribute(RegistryConstants.ATTR_TYPE));

        String osName = config.getAttribute(RegistryConstants.ATTR_OS);
        this.system = osName == null ? null : new OSDescriptor(
            osName,
            config.getAttribute(RegistryConstants.ATTR_ARCH));
        this.path = config.getAttribute(RegistryConstants.ATTR_PATH);
        this.description = config.getAttribute(RegistryConstants.ATTR_DESCRIPTION);
        this.custom = false;
    }

    public DriverDescriptor getDriver()
    {
        return driver;
    }

    @NotNull
    @Override
    public FileType getType()
    {
        return type;
    }

    @NotNull
    @Override
    public String getPath()
    {
        return path;
    }

    @Override
    public String getDescription()
    {
        return description;
    }

    @Override
    public boolean isCustom()
    {
        return custom;
    }

    public void setCustom(boolean custom)
    {
        this.custom = custom;
    }

    @Override
    public boolean isDisabled()
    {
        return disabled;
    }

    public void setDisabled(boolean disabled)
    {
        this.disabled = disabled;
    }

    @Override
    public boolean isDownloadable()
    {
        return isRepositoryArtifact() || isMavenArtifact();
    }

    private boolean isRepositoryArtifact() {
        return path.startsWith(FILE_SOURCE_REPO);
    }

    @Override
    public boolean isMavenArtifact() {
        return path.startsWith(FILE_SOURCE_MAVEN);
    }

    public boolean isMavenArtifactResolved() {
        MavenArtifact artifact = getMavenArtifact();
        return artifact != null && artifact.getActiveLocalVersion() != null;
    }

    private String getRepositoryPath() {
        return path.substring(FILE_SOURCE_REPO.length());
    }

    @Nullable
    private MavenArtifact getMavenArtifact() {
        return MavenRegistry.getInstance().findArtifact(new MavenArtifactReference(path));
    }

    private File detectLocalFile()
    {
        if (isMavenArtifact()) {
            MavenArtifact artifact = getMavenArtifact();
            if (artifact != null) {
                MavenLocalVersion localVersion = artifact.getActiveLocalVersion();
                if (localVersion == null && artifact.getRepository().isLocal()) {
                    // In case of local artifact make version resolve
                    MavenArtifactReference artifactInfo = new MavenArtifactReference(path);
                    try {
                        localVersion = artifact.resolveVersion(VoidProgressMonitor.INSTANCE, artifactInfo.getVersion());
                    } catch (IOException e) {
                        log.warn("Error resolving local artifact [" + artifact + "] version", e);
                    }
                }
                if (localVersion != null) {
                    return localVersion.getCacheFile();
                }
            }
            return null;
        }
        String localPath;
        if (isRepositoryArtifact()) {
            localPath = getRepositoryPath();
        } else {
            localPath = path;
        }
        // Try to use relative path from installation dir
        File file = new File(new File(Platform.getInstallLocation().getURL().getFile()), localPath);
        if (!file.exists()) {
            // Use custom drivers path
            file = new File(DriverDescriptor.getCustomDriversHome(), localPath);
        }
        return file;
    }

    @Nullable
    @Override
    public String getExternalURL() {
        if (path.startsWith(FILE_SOURCE_PLATFORM)) {
            return path;
        } else if (isMavenArtifact()) {
            MavenArtifact artifact = getMavenArtifact();
            if (artifact != null) {
                MavenLocalVersion localVersion = artifact.getActiveLocalVersion();
                if (localVersion != null) {
                    return localVersion.getExternalURL(MavenArtifact.FILE_JAR);
                }
            }
            return null;
        } else {
            String localPath;
            if (isRepositoryArtifact()) {
                localPath = getRepositoryPath();
            } else {
                localPath = path;
            }

            String primarySource = DriverDescriptor.getDriversPrimarySource();
            if (!primarySource.endsWith("/") && !localPath.startsWith("/")) {
                primarySource += '/';
            }
            return primarySource + localPath;
        }
    }


    @Nullable
    @Override
    public File getLocalFile()
    {
        if (path.startsWith(FILE_SOURCE_PLATFORM)) {
            try {
                return RuntimeUtils.getPlatformFile(path);
            } catch (IOException e) {
                log.warn("Bad file URL: " + path, e);
            }
        }
        boolean externalPath = isRepositoryArtifact() || isMavenArtifact();
        if (!externalPath) {
            // Try to use direct path
            File libraryFile = new File(path);
            if (libraryFile.exists()) {
                return libraryFile;
            }
        }
        // Try to get local file
        File platformFile = detectLocalFile();
        if (platformFile != null && platformFile.exists()) {
            // Relative file do not exists - use plain one
            return platformFile;
        }

        // Try to get from plugin's bundle/from external resources
        if (!externalPath) {
            URL url = driver.getProviderDescriptor().getContributorBundle().getEntry(path);
            if (url == null) {
                // Find in external resources
                url = driver.getProviderDescriptor().getRegistry().findResourceURL(path);
            }
            if (url != null) {
                try {
                    url = FileLocator.toFileURL(url);
                }
                catch (IOException ex) {
                    log.warn(ex);
                }
            }
            if (url != null) {
                return new File(url.getFile());
            }
        }

        // Nothing fits - just return plain url
        return platformFile;
    }

    @Override
    public boolean matchesCurrentPlatform()
    {
        return system == null || system.matches(DBeaverCore.getInstance().getLocalSystem());
    }

    @Nullable
    @Override
    public Collection<DBPDriverLibrary> getDependencies() {
        return null;
    }

    @NotNull
    public String getDisplayName() {
        if (isMavenArtifact()) {
            MavenArtifact artifact = getMavenArtifact();
            if (artifact != null) {
                MavenLocalVersion version = artifact.getActiveLocalVersion();
                if (version != null) {
                    return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + version.getVersion();
                }
            }
        }

        return path;
    }

    @NotNull
    @Override
    public DBIcon getIcon() {
        if (isMavenArtifact()) {
            return UIIcon.APACHE;
        }

        File localFile = getLocalFile();
        if (localFile != null && localFile.isDirectory()) {
            return DBIcon.TREE_FOLDER;
        } else {
            switch (type) {
                case lib: return UIIcon.LIBRARY;
                case jar: return UIIcon.JAR;
                default: return DBIcon.TYPE_UNKNOWN;
            }
        }
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

}