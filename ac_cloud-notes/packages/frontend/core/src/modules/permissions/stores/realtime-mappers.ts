import { DocRole, Permission, WorkspaceMemberStatus } from '@cloud-notes/graphql';
import type {
  DocGrantedUserSnapshot,
  WorkspaceMemberSnapshot,
} from '@cloud-notes/realtime';

import { mapRealtimeEnum } from '../../cloud/realtime/enum';

export function mapWorkspaceMemberSnapshot(member: WorkspaceMemberSnapshot) {
  return {
    ...member,
    permission: mapRealtimeEnum(Permission, member.permission, 'permission'),
    role: mapRealtimeEnum(Permission, member.role, 'permission'),
    status: mapRealtimeEnum(
      WorkspaceMemberStatus,
      member.status,
      'workspace member status'
    ),
  };
}

export function mapDocGrantedUserSnapshot(node: DocGrantedUserSnapshot) {
  return { ...node, role: mapRealtimeEnum(DocRole, node.role, 'doc role') };
}
