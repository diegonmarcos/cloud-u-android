import 'dart:io';

import 'package:GitSync/api/manager/storage.dart';
import 'package:GitSync/api/accessibility_service_helper.dart';

class RepoManager extends Storage {
  RepoManager({super.name = "git_sync_repos"});

  Future<String> getRepoName(int index) async {
    final repomanReponames = await getStringList(StorageKey.repoman_repoNames);
    if (index >= repomanReponames.length) {
      index = repomanReponames.length - 1;
      setInt(StorageKey.repoman_repoIndex, index);
    }
    return (await getStringList(StorageKey.repoman_repoNames))[index];
  }

  Future<void> setOnboardingStep(int step) async {
    if (await getInt(StorageKey.repoman_onboardingStep) == -1) return;
    await setInt(StorageKey.repoman_onboardingStep, step);
  }

  Future<bool> hasLegacySettings() async {
    if (Platform.isIOS) return false;
    if (await getInt(StorageKey.repoman_onboardingStep) != 0) return false;

    return await AccessibilityServiceHelper.hasLegacySettings();
  }
}
