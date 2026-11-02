// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The text and date of a chat list row (spec chat-list; ChatListModel.rowPreview and
// TimelineItemContent.previewText on iOS): the message text, a media kind label, Sticker or Localização,
// a line for a join, a leave or the room creation, with "Você: " before an own message. The date is the
// time today, else the day and the short month (ChatRow.timeFormat).

import {
  MembershipChange,
  MessageType,
  MsgLikeKind,
  OtherState,
  TimelineItemContent,
} from './generated/matrix_sdk_ffi';
import type { Latest } from './chat-model';
import { mediaOf } from './TimelineMedia';
import { locale, s } from './ui/i18n';

/** The plain preview of a message (previewText on iOS); undefined for anything without one. */
export function previewText(content: TimelineItemContent): string | undefined {
  if (!TimelineItemContent.MsgLike.instanceOf(content)) return undefined;
  const kind = content.inner.content.kind;
  if (MsgLikeKind.Message.instanceOf(kind)) {
    const type = kind.inner.content.msgType;
    const media = mediaOf(type);
    if (media) {
      if (media.kind === 'audio') return media.isVoice ? s('bipe_app_media_voice') : media.filename;
      return media.kind === 'image'
        ? s('bipe_app_media_image')
        : media.kind === 'video'
          ? s('bipe_app_media_video')
          : s('bipe_app_media_file');
    }
    if (MessageType.Location.instanceOf(type)) return s('bipe_app_location_title');
    return kind.inner.content.body;
  }
  if (MsgLikeKind.Sticker.instanceOf(kind)) return s('bipe_app_media_sticker');
  if (MsgLikeKind.UnableToDecrypt.instanceOf(kind)) return s('bipe_web_timeline_undecryptable');
  return undefined;
}

function membershipLine(latest: Latest, isDm: boolean, ownUserId: string): string | undefined {
  const content = latest.content;
  if (!content) return undefined;
  if (TimelineItemContent.RoomMembership.instanceOf(content)) {
    const { userId, userDisplayName, change } = content.inner;
    const own = userId === ownUserId;
    const name = userDisplayName ?? userId;
    if (
      change === MembershipChange.Joined ||
      change === MembershipChange.InvitationAccepted ||
      change === MembershipChange.KnockAccepted
    ) {
      if (isDm) return own ? s('bipe_dm_joined_by_you') : s('bipe_dm_joined', name);
      return own ? s('bipe_group_state_event_room_join_by_you') : s('bipe_group_state_event_room_join', name);
    }
    if (change === MembershipChange.Left) {
      if (isDm) return own ? s('bipe_dm_left_by_you') : s('bipe_dm_left', name);
      return own ? s('bipe_group_state_event_room_leave_by_you') : s('bipe_group_state_event_room_leave', name);
    }
    return undefined;
  }
  if (TimelineItemContent.State.instanceOf(content) && OtherState.RoomCreate.instanceOf(content.inner.content)) {
    const name = latest.senderName ?? latest.sender;
    if (isDm) return latest.isOwn ? s('bipe_dm_created_by_you') : s('bipe_dm_created', name);
    return latest.isOwn
      ? s('bipe_group_state_event_room_created_by_you')
      : s('bipe_group_state_event_room_created', name);
  }
  return undefined;
}

export function rowPreview(latest: Latest | undefined, isDm: boolean, ownUserId: string): string | undefined {
  if (!latest?.content) return undefined;
  const line = membershipLine(latest, isDm, ownUserId);
  if (line) return line;
  const text = previewText(latest.content);
  if (text === undefined) return undefined;
  return latest.isOwn ? s('bipe_chat_list_own_preview', text) : text;
}

const today = new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' });
const earlier = new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' });

export function rowTime(ms: number): string {
  const date = new Date(ms);
  return date.toDateString() === new Date().toDateString() ? today.format(date) : earlier.format(date);
}
