export type RemovedUserInfo = {
  id: string;
  removed: true;
};

export type ExistedUserInfo = {
  id: string;
  name?: string | null;
  avatar?: string | null;
  removed?: false;
};

export type CloudNotesUserInfo = RemovedUserInfo | ExistedUserInfo;

export function isRemovedUserInfo(
  userInfo: CloudNotesUserInfo
): userInfo is RemovedUserInfo {
  return Boolean('removed' in userInfo && userInfo.removed);
}

export function isExistedUserInfo(
  userInfo: CloudNotesUserInfo
): userInfo is ExistedUserInfo {
  return !isRemovedUserInfo(userInfo);
}
