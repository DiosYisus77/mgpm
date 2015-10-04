package io.bit3.mgpm.cli;

import io.bit3.mgpm.cmd.Args;
import io.bit3.mgpm.config.Config;
import io.bit3.mgpm.config.RepositoryConfig;
import io.bit3.mgpm.worker.AbstractWorkerObserver;
import io.bit3.mgpm.worker.FromToIsh;
import io.bit3.mgpm.worker.LoggingWorkerObserver;
import io.bit3.mgpm.worker.Update;
import io.bit3.mgpm.worker.Upstream;
import io.bit3.mgpm.worker.Worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class CliApplication {
  private final Logger logger = LoggerFactory.getLogger(CliApplication.class);
  private final Args args;
  private final Config config;
  private final AnsiOutput output;
  private final int repositoryPadding;

  public CliApplication(Args args, Config config) {
    this.args = args;
    this.config = config;
    this.output = AnsiOutput.getInstance();
    this.repositoryPadding = config.getRepositories()
        .stream()
        .mapToInt(r -> r.getName().length())
        .max()
        .getAsInt();
  }

  public void run() {
    List<File> knownDirectories = new LinkedList<>();

    int cores = Runtime.getRuntime().availableProcessors();
    ExecutorService executor = Executors.newFixedThreadPool(2 * cores);

    for (final RepositoryConfig repositoryConfig : config.getRepositories()) {
      knownDirectories.add(repositoryConfig.getDirectory());

      Worker worker = new Worker(config, repositoryConfig, args.isDoInit(), args.isDoUpdate());
      worker.registerObserver(new LoggingWorkerObserver(args, output, repositoryPadding));
      worker.registerObserver(new CliWorkerObserver());
      executor.submit(worker);
    }
    executor.shutdown();

    while (!executor.isTerminated()) {
      synchronized (output) {
        output.rotateSpinner();
      }

      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        break;
      }
    }

    output.deleteSpinner();

    if (args.isShowStatus()) {
      printSuperfluousDirectories(knownDirectories);
    }
  }

  private void printSuperfluousDirectories(List<File> knownDirectories) {
    Set<File> parentDirectories = knownDirectories
        .stream()
        .map(File::getParentFile)
        .collect(Collectors.toCollection(TreeSet::new));
    Set<File> seenFiles = new TreeSet<>();

    for (File parentDirectory : parentDirectories) {
      File[] files = parentDirectory.listFiles();
      if (null != files)
        Collections.addAll(seenFiles, files);
    }

    seenFiles.removeAll(knownDirectories);

    URI workingDirectory = Paths.get(".").toAbsolutePath().normalize().toUri();
    for (File file : seenFiles) {
      String relativePath = workingDirectory.relativize(file.toURI()).getPath();
      relativePath = relativePath.replaceFirst("/$", "");
      output
          .print(" * ")
          .print(Color.YELLOW, relativePath)
          .print(" ")
          .print(Color.RED, "superfluous")
          .println();
    }
  }

  private class CliWorkerObserver extends AbstractWorkerObserver {
    @Override
    public void end(Worker worker) {
      try {
        List<String> localBranchNames = worker.getLocalBranchNames();
        Map<String, List<String>> remoteBranchNames = worker.getRemoteBranchNames();

        if (localBranchNames.isEmpty() && (!args.isShowStatus() || remoteBranchNames.isEmpty())) {
          return;
        }

        Map<String, List<String>> addedRemoteBranchNames = worker.getAddedRemoteBranchNames();
        Map<String, List<String>> deletedRemoteBranchNames = worker.getDeletedRemoteBranchNames();
        Map<String, Upstream> branchUpstreamMap = worker.getBranchUpstreamMap();
        Map<String, Update> branchUpdateStatus = worker.getBranchUpdateStatus();
        Map<String, FromToIsh> branchUpdateIsh = worker.getBranchUpdateIsh();
        Map<String, Worker.Stats> branchStats = worker.getBranchStats();
        Map<String, List<String>> remoteBranchesUsedAsUpstream = new HashMap<>();

        int padding = localBranchNames.stream().mapToInt(String::length).max().getAsInt();
        String pattern = "%-" + padding + "s";

        synchronized (output) {
          output.deleteSpinner();

          output
              .print(" * ")
              .print(Color.YELLOW, worker.getRepositoryConfig().getName())
              .println();

          for (String branchName : localBranchNames) {
            Upstream upstream = branchUpstreamMap.get(branchName);
            Update update = branchUpdateStatus.get(branchName);
            Worker.Stats stats = branchStats.get(branchName);
            String headSymbolicRef = worker.getHeadSymbolicRef();

            if (null != upstream) {
              List<String> branches = remoteBranchesUsedAsUpstream.get(upstream.getRemoteName());

              if (null == branches) {
                branches = new LinkedList<>();
                remoteBranchesUsedAsUpstream.put(upstream.getRemoteName(), branches);
              }

              branches.add(upstream.getRemoteBranch());
            }

            printBranchName(pattern, branchName, headSymbolicRef);
            printBranchUpstream(remoteBranchNames, upstream);
            printBranchUpdate(branchName, update, branchUpdateIsh);
            printBranchStats(stats);

            output.println();
          }

          if (args.isShowStatus()) {
            printRemoteBranches(pattern, remoteBranchNames, addedRemoteBranchNames, deletedRemoteBranchNames, remoteBranchesUsedAsUpstream);
          }

          output.println();
        }
      } catch (Exception exception) {
        logger.error(exception.getMessage(), exception);
      }
    }

    private void printBranchName(String pattern, String branchName, String headSymbolicRef) {
      output.print("   ");

      boolean isHead = branchName.equals(headSymbolicRef);
      if (isHead) {
        output.print(Color.BLUE, "> ");
      } else {
        output.print("  ");
      }

      output.print(pattern, branchName);
    }

    private void printBranchUpstream(Map<String, List<String>> remoteBranchNames, Upstream upstream) {
      if (null != upstream) {
        output.print(Color.DARK_GRAY, " → %s", upstream.getRemoteRef());
      }
    }

    private void printBranchUpdate(String branchName, Update update, Map<String, FromToIsh> branchUpdateIsh) {
      if (null != update) {
        Color color = Color.YELLOW;
        switch (update) {
          case SKIP_NO_UPSTREAM:
            return;

          case SKIP_UPSTREAM_DELETED:
          case SKIP_CONFLICTING:
            color = Color.LIGHT_RED;
            break;

          case UP_TO_DATE:
          case MERGED_FAST_FORWARD:
          case REBASED:
            color = Color.GREEN;
            break;
        }

        output
            .print(" ")
            .print(color, update.toString().toLowerCase().replace('_', ' '));

        switch (update) {
          case MERGED_FAST_FORWARD:
          case REBASED:
            FromToIsh fromToIsh = branchUpdateIsh.get(branchName);
            output
                .print(" ")
                .print(fromToIsh.getFrom().substring(0, 8))
                .print("..")
                .print(fromToIsh.getTo().substring(0, 8));
        }
      }
    }

    private void printBranchStats(Worker.Stats stats) {
      if (null != stats) {
        if (0 == stats.getCommitsBehind() && 0 == stats.getCommitsAhead() && stats.isClean()) {
          output.print(Color.GREEN, "  ✔");
        }

        if (stats.getCommitsBehind() > 0) {
          output.print(Color.CYAN, "  ↓");
          output.print(Color.CYAN, stats.getCommitsBehind());
        }

        if (stats.getCommitsAhead() > 0) {
          output.print(Color.CYAN, "  ↑");
          output.print(Color.CYAN, stats.getCommitsAhead());
        }

        if (stats.getAdded() > 0) {
          output.print(Color.RED, "  +");
          output.print(Color.RED, stats.getAdded());
        }

        if (stats.getModified() > 0) {
          output.print(Color.YELLOW, "  ★");
          output.print(Color.YELLOW, stats.getModified());
        }

        if (stats.getRenamed() > 0) {
          output.print(Color.MAGENTA, "  ⇄");
          output.print(Color.MAGENTA, stats.getRenamed());
        }

        if (stats.getCopied() > 0) {
          output.print(Color.MAGENTA, "  ↷");
          output.print(Color.MAGENTA, stats.getCopied());
        }

        if (stats.getDeleted() > 0) {
          output.print(Color.MAGENTA, "  -");
          output.print(Color.MAGENTA, stats.getDeleted());
        }

        if (stats.getUnmerged() > 0) {
          output.print(Color.RED, "  ☠");
          output.print(Color.RED, stats.getUnmerged());
        }
      }
    }

    private void printRemoteBranches(String pattern, Map<String, List<String>> remoteBranchNames, Map<String, List<String>> addedRemoteBranchNames, Map<String, List<String>> deletedRemoteBranchNames, Map<String, List<String>> remoteBranchesUsedAsUpstream) {
      for (Map.Entry<String, List<String>> entry : remoteBranchNames.entrySet()) {
        String remoteName = entry.getKey();
        List<String> currentBranchNames = entry.getValue();
        List<String> addedBranchNames = addedRemoteBranchNames.get(remoteName);
        List<String> deletedBranchNames = deletedRemoteBranchNames.get(remoteName);

        Set<String> remoteBranches = new TreeSet<>();
        remoteBranches.addAll(currentBranchNames);
        if (null != addedBranchNames) {
          remoteBranches.addAll(addedBranchNames);
        }
        if (null != deletedBranchNames) {
          remoteBranches.addAll(deletedBranchNames);
        }

        for (String remoteBranch : remoteBranches) {
          boolean usedAsUpstream = remoteBranchesUsedAsUpstream.containsKey(remoteName)
              && remoteBranchesUsedAsUpstream.get(remoteName).contains(remoteBranch);

          if (usedAsUpstream) {
            continue;
          }

          output
              .print("   ")
              .print(Color.DARK_GRAY, pattern, remoteName + "/" + remoteBranch);

          boolean wasAdded = null != addedBranchNames
              && addedBranchNames.contains(remoteBranch);

          boolean wasDeleted = null != deletedBranchNames
              && deletedBranchNames.contains(remoteBranch);

          if (wasAdded) {
            output.print(Color.GREEN, " (added)");
          } else if (wasDeleted) {
            output.print(Color.RED, " (removed)");
          }

          output.println();
        }
      }
    }
  }
}
