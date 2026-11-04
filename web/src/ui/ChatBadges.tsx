// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The marks of a chat, for a row and a Mural tile (spec chat-list): the muted bell, the @ mark, and the
// unread count or the marked-unread dot.

import { RoomNotificationMode, type RoomInfo } from '../generated/matrix_sdk_ffi';
import { bellSlash } from '../phosphor/icons';
import { s } from './i18n';
import { Icon } from './Icon';

/** The accessibility label of the unread count, with the mention when there is one. */
export function unreadLabel(unread: number, mentions: number): string {
  const count = s('bipe_app_chat_list_unread', unread.toLocaleString());
  return mentions > 0 ? `${count}, ${s('bipe_a11y_mention')}` : count;
}

export function ChatBadges({ info }: { info?: RoomInfo }) {
  const unread = Number(info?.numUnreadMessages ?? 0);
  const mentions = Number(info?.numUnreadMentions ?? 0);
  const muted = info?.cachedUserDefinedNotificationMode === RoomNotificationMode.Mute;
  return (
    <>
      {muted && (
        <Icon
          path={bellSlash}
          size={16}
          label={s('bipe_app_notification_mode_off')}
          className="shrink-0 text-ink-secondary"
        />
      )}
      {unread > 0 ? (
        <>
          {mentions > 0 && (
            <span
              aria-hidden
              className="grid size-5 shrink-0 place-items-center rounded-full bg-accent-fill text-caption font-bold text-ink-on-accent"
            >
              @
            </span>
          )}
          <span
            className="shrink-0 rounded-full bg-accent-fill px-[7px] py-0.5 text-caption font-bold text-ink-on-accent"
            aria-label={unreadLabel(unread, mentions)}
          >
            {unread.toLocaleString()}
          </span>
        </>
      ) : (
        info?.isMarkedUnread && (
          <span
            role="img"
            aria-label={s('bipe_chat_unread_state')}
            className="size-2 shrink-0 rounded-full bg-accent-fill"
          />
        )
      )}
    </>
  );
}
