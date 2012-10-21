package jetbrains.buildServer.artifactsMirror;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.artifactsMirror.seeder.FileLink;
import jetbrains.buildServer.artifactsMirror.seeder.TorrentFileFactory;
import jetbrains.buildServer.artifactsMirror.seeder.TorrentsDirectorySeeder;
import jetbrains.buildServer.artifactsMirror.torrent.TorrentUtil;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Map;

/**
 * User: Victory.Bedrosova
 * Date: 10/9/12
 * Time: 5:12 PM
 */
public class AgentTorrentsManager extends AgentLifeCycleAdapter implements ArtifactsPublisher {
  private final static Logger LOG = Logger.getInstance(AgentTorrentsManager.class.getName());

  private static final String TORRENT_FOLDER_NAME = "torrents";

  @NotNull
  private final TorrentTrackerConfiguration myTrackerManager;
  private volatile URI myTrackerAnnounceUrl;
  private volatile Integer myFileSizeThresholdMb;
  private TorrentsDirectorySeeder myTorrentsDirectorySeeder;
  private AgentRunningBuild myBuild;

  public AgentTorrentsManager(@NotNull BuildAgentConfiguration agentConfiguration,
                              @NotNull EventDispatcher<AgentLifeCycleListener> eventDispatcher,
                              @NotNull TorrentTrackerConfiguration trackerManager) throws Exception {
    eventDispatcher.addListener(this);

    File torrentsStorage = agentConfiguration.getCacheDirectory(TORRENT_FOLDER_NAME);
    myTrackerManager = trackerManager;
    myTorrentsDirectorySeeder = new TorrentsDirectorySeeder(torrentsStorage, new TorrentFileFactory() {
      @Nullable
      public File createTorrentFile(@NotNull File sourceFile, @NotNull File parentDir) throws IOException {
        return AgentTorrentsManager.this.createTorrentFile(sourceFile, parentDir);
      }
    });
  }

  private File createTorrentFile(File sourceFile, File parentDir) {
    if (!settingsInited()) return null;
    if (shouldCreateTorrentFileFor(sourceFile)) {
      File torrentFile = new File(parentDir, sourceFile.getName() + TorrentUtil.TORRENT_FILE_SUFFIX);
      TorrentUtil.createTorrent(sourceFile, torrentFile, myTrackerAnnounceUrl);
      return torrentFile;
    }
    return null;
  }

  private boolean settingsInited() {
    return myTrackerAnnounceUrl != null && myFileSizeThresholdMb != null;
  }

  private boolean initSettings() {
    try {
      myTrackerAnnounceUrl = new URI(myTrackerManager.getAnnounceUrl());
    } catch (URISyntaxException e) {
      LOG.warn(e.toString(), e);
      return false;
    }
    myFileSizeThresholdMb = myTrackerManager.getFileSizeThresholdMb();
    return true;
  }

  @Override
  public void agentStarted(@NotNull BuildAgent agent) {
    try {
      myTorrentsDirectorySeeder.start(InetAddress.getByName(agent.getConfiguration().getOwnAddress()));
    } catch (UnknownHostException e) {
      Loggers.AGENT.error("Failed to start torrent seeder, error: " + e.toString());
    }
  }

  @Override
  public void buildStarted(@NotNull AgentRunningBuild runningBuild) {
    initSettings();
    myBuild = runningBuild;
  }

  @Override
  public void agentShutdown() {
    myTorrentsDirectorySeeder.stop();
  }

  private boolean announceNewFile(@NotNull File srcFile) {
    if (!settingsInited()) return false;
    if (shouldCreateTorrentFileFor(srcFile)) {
      File linkDir;
      if (srcFile.getAbsolutePath().startsWith(myBuild.getCheckoutDirectory().getAbsolutePath())) {
        String relPath = FileUtil.getRelativePath(myBuild.getCheckoutDirectory(), srcFile);
        linkDir = new File(myTorrentsDirectorySeeder.getStorageDirectory(), myBuild.getBuildTypeId() + File.separator + relPath).getParentFile();
      } else {
        linkDir = new File(myTorrentsDirectorySeeder.getStorageDirectory(), myBuild.getBuildTypeId());
      }
      linkDir.mkdirs();
      if (!linkDir.isDirectory()) return false;
      try {
        FileLink.createLink(srcFile, linkDir);
      } catch (IOException e) {
        return false;
      }
    }

    return true;
  }

  private boolean shouldCreateTorrentFileFor(File srcFile) {
    return srcFile.length() >= myFileSizeThresholdMb * 1024 * 1024;
  }

  public int publishFiles(@NotNull Map<File, String> fileStringMap) throws ArtifactPublishingFailedException {
    return announceBuildArtifacts(fileStringMap.keySet());
  }

  private int announceBuildArtifacts(@NotNull Collection<File> artifacts) {
    int num = 0;
    for (File artifact : artifacts) {
      if (announceNewFile(artifact)) ++num;
    }
    return num;
  }
}
