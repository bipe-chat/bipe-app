// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The tiles of Mural (spec chat-list, ADR 0246). The tier sets the tile and nothing else sets it. Tier 1: full
// width, avatar, name and messages; the first tile also has the draft and a reply field. Tier 2: half width,
// avatar, name, reason and one bubble. Tier 3: one third, avatar, first word of the name and two lines of text.
// Tier 4: avatar only, under Sem novidade. The last row of a tier stretches. Right-click opens the same menu
// as a row.

import { useEffect, useState, type MouseEvent } from 'react';
import { useAccounts } from './accounts-context';
import { contentWithMentions } from './mentions';
import { markRead, useIgnoredUsers } from './chat-actions';
import type { ChatSummary } from './chat-model';
import { ComposerDraftType } from './generated/matrix_sdk_ffi';
import { useClient } from './media';
import { tileMessages, type MuralTile, type TileMessage } from './mural-plan';
import { paperPlaneRight } from './phosphor/icons';
import { useIsNewsRoom } from './news-room';
import { homeScore } from './registries';
import { ChatOfficialBadge } from './ui/ChatOfficialBadge';
import { Avatar, useIsCompany } from './ui/Avatar';
import { ChatBadges, unreadLabel } from './ui/ChatBadges';
import { s } from './ui/i18n';
import { Icon } from './ui/Icon';
import { describe } from './util';

type TileHandlers = {
  openId?: string;
  onOpen: (chat: ChatSummary) => void;
  onMenu: (chat: ChatSummary, e: MouseEvent) => void;
};

// The grid has 6 columns: a tile of a tier with n columns spans 6 / n.
const SPAN: Record<number, string> = { 2: 'col-span-2', 3: 'col-span-3', 6: 'col-span-6' };
const GRID_TIERS = [1, 2, 3];

function spansOf(tiles: MuralTile[]): { tile: MuralTile; span: number }[] {
  return GRID_TIERS.flatMap((tier) => {
    const inTier = tiles.filter((t) => t.tier === tier);
    const columns = homeScore.tiers.find((t) => t.tier === tier)?.columns ?? 1;
    const left = inTier.length % columns;
    return inTier.map((tile, i) => ({
      tile,
      span: left > 0 && i >= inTier.length - left ? 6 / left : 6 / columns,
    }));
  });
}

/** Spec chat-list, Mural grid rules: the screen reader reads the name, the reason and the unread count. */
function labelOf({ chat, reason }: MuralTile): string {
  const unread = Number(chat.info?.numUnreadMessages ?? 0);
  const parts = [chat.face.name];
  if (reason === 'mention') parts.push(s('bipe_chat_mural_reason_mention'));
  if (reason === 'unanswered') parts.push(s('bipe_chat_mural_reason_unanswered'));
  if (unread > 0) parts.push(unreadLabel(unread, 0));
  else if (chat.info?.isMarkedUnread) parts.push(s('bipe_chat_unread_state'));
  return parts.join(', ');
}

export function Mural({
  tiles,
  onViewList,
  ...handlers
}: TileHandlers & {
  tiles: MuralTile[];
  onViewList: () => void;
}) {
  const quiet = tiles.filter((t) => t.tier === 4);
  const first = tiles.find((t) => t.tier === 1);
  return (
    <li className="flex flex-col gap-3 px-2.5 pt-1 pb-2">
      <ul className="grid grid-cols-6 gap-2">
        {spansOf(tiles).map(({ tile, span }) => (
          <li key={tile.chat.roomId} className={`min-w-0 ${SPAN[span]}`}>
            {tile.tier === 1 ? (
              <LargeTile tile={tile} isFirst={tile === first} {...handlers} />
            ) : tile.tier === 2 ? (
              <MediumTile tile={tile} {...handlers} />
            ) : (
              <SmallTile tile={tile} {...handlers} />
            )}
          </li>
        ))}
      </ul>
      {quiet.length > 0 && (
        <section className="flex flex-col gap-2 px-1.5">
          <h2 className="text-footnote text-ink-secondary">{s('bipe_chat_mural_no_news')}</h2>
          <ul className="flex flex-wrap gap-2">
            {quiet.map((tile) => (
              <li key={tile.chat.roomId}>
                <QuietTile tile={tile} {...handlers} />
              </li>
            ))}
          </ul>
        </section>
      )}
      <button
        type="button"
        className="min-h-11 w-full rounded-xl text-subheadline font-semibold text-accent-ink hover:bg-surface-raised"
        onClick={onViewList}
      >
        {s('bipe_chat_mural_view_list')}
      </button>
    </li>
  );
}

// The props of the button of a tile: the chat, its menu, its label and its selected state.
function buttonProps(tile: MuralTile, { openId, onOpen, onMenu }: TileHandlers) {
  return {
    type: 'button' as const,
    'aria-label': labelOf(tile),
    'aria-current': openId === tile.chat.roomId || undefined,
    onClick: () => onOpen(tile.chat),
    onContextMenu: (e: MouseEvent) => onMenu(tile.chat, e),
  };
}

// A selected tile has a ring, so the state does not show by colour alone.
const selectedRing = (tile: MuralTile, openId?: string) =>
  openId === tile.chat.roomId ? 'ring-2 ring-accent-fill' : '';

function TileAvatar({ chat, size, dot }: { chat: ChatSummary; size: number; dot?: boolean }) {
  const { name, avatarUrl, peer, members } = chat.face;
  const newsRoom = useIsNewsRoom(chat.roomId);
  const unread = Number(chat.info?.numUnreadMessages ?? 0) > 0 || chat.info?.isMarkedUnread === true;
  return (
    <span className="relative shrink-0">
      <Avatar name={name} size={size} mxcUrl={avatarUrl} userId={peer?.userId} official={newsRoom} members={members} />
      {dot && unread && (
        <span className="absolute top-0 right-0 size-[14px] rounded-full border-2 border-surface-base bg-accent-fill" />
      )}
    </span>
  );
}

function TileBubble({ message, clamp }: { message: TileMessage; clamp: string }) {
  return (
    <span
      className={`block max-w-full rounded-2xl px-3 py-2 ${message.own ? 'self-end bg-bubble-own' : 'self-start bg-bubble-other'}`}
    >
      <span className={`block text-subheadline break-words text-ink-primary ${clamp}`}>{message.text}</span>
    </span>
  );
}

function LargeTile({ tile, isFirst, ...handlers }: TileHandlers & { tile: MuralTile; isFirst: boolean }) {
  const { chat } = tile;
  const count = isFirst ? homeScore.tile.firstTileMessages : homeScore.tile.secondTileMessages;
  return (
    <div className={`flex flex-col rounded-[22px] bg-accent-wash ${selectedRing(tile, handlers.openId)}`}>
      <button {...buttonProps(tile, handlers)} className="flex w-full flex-col gap-2 rounded-[22px] p-3.5 text-left">
        <span className="flex items-center gap-3">
          <TileAvatar chat={chat} size={64} />
          <span className="min-w-0 flex-1 truncate text-headline text-ink-primary">{chat.face.name}</span>
          <ChatOfficialBadge roomId={chat.roomId} peerId={chat.face.peer?.userId} />
          <ChatBadges info={chat.info} />
        </span>
        <span className="flex flex-col gap-1">
          {tileMessages(chat, count).map((message, i) => (
            <TileBubble key={i} message={message} clamp="line-clamp-4" />
          ))}
        </span>
      </button>
      {isFirst && <TileReply key={chat.roomId} chat={chat} />}
    </div>
  );
}

function MediumTile({ tile, ...handlers }: TileHandlers & { tile: MuralTile }) {
  const { chat, reason } = tile;
  const [message] = tileMessages(chat, homeScore.tile.tier2Bubbles);
  return (
    <button
      {...buttonProps(tile, handlers)}
      className={`flex size-full flex-col gap-2 rounded-[22px] bg-surface-raised p-3 text-left hover:bg-surface-sunken ${selectedRing(tile, handlers.openId)}`}
    >
      <span className="flex items-center gap-2">
        <TileAvatar chat={chat} size={44} />
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="flex items-center gap-1">
            <span className="min-w-0 flex-1 truncate text-subheadline font-semibold text-ink-primary">
              {chat.face.name}
            </span>
            <ChatOfficialBadge roomId={chat.roomId} peerId={chat.face.peer?.userId} />
          </span>
          {reason && (
            <span className="truncate text-caption font-semibold text-accent-ink">
              {reason === 'mention' ? s('bipe_chat_mural_reason_mention') : s('bipe_chat_mural_reason_unanswered')}
            </span>
          )}
        </span>
        <ChatBadges info={chat.info} />
      </span>
      {message && <TileBubble message={message} clamp="line-clamp-3" />}
    </button>
  );
}

function SmallTile({ tile, ...handlers }: TileHandlers & { tile: MuralTile }) {
  const { chat } = tile;
  const firstWord = chat.face.name.trim().split(/\s+/)[0];
  return (
    <button
      {...buttonProps(tile, handlers)}
      className={`flex size-full flex-col items-center gap-1 rounded-[22px] bg-surface-raised px-1.5 py-3 text-center hover:bg-surface-sunken ${selectedRing(tile, handlers.openId)}`}
    >
      <TileAvatar chat={chat} size={44} dot />
      <span className="flex max-w-full items-center gap-1">
        <span className="min-w-0 truncate text-footnote font-semibold text-ink-primary">{firstWord}</span>
        <ChatOfficialBadge roomId={chat.roomId} peerId={chat.face.peer?.userId} />
      </span>
      {/* Two lines: registries homeScore tile.tier3TextLines. Tailwind needs the whole class name in the source. */}
      <span className="line-clamp-2 max-w-full text-caption break-words text-ink-secondary">
        {chat.row.preview ?? ''}
      </span>
    </button>
  );
}

function QuietTile({ tile, ...handlers }: TileHandlers & { tile: MuralTile }) {
  return (
    <button
      {...buttonProps(tile, handlers)}
      className={`grid min-h-11 min-w-11 place-items-center rounded-full ${selectedRing(tile, handlers.openId)}`}
    >
      <TileAvatar chat={tile.chat} size={44} dot />
    </button>
  );
}

// The reply field of the first tile (spec chat-list replyFromTile): the send rules of the composer, here as a
// plain text message. It starts with the saved draft of the room. A blocked person's DM has no field, as the
// composer is off there (spec block-person).
function TileReply({ chat }: { chat: ChatSummary }) {
  const client = useClient();
  const ignored = useIgnoredUsers(client);
  const { current: account } = useAccounts();
  // ADR 0121: a company account writes as the company; the composer says so.
  const companyName = useIsCompany(client.userId()) ? (account.name ?? undefined) : undefined;
  const [text, setText] = useState('');
  const [draft, setDraft] = useState<string>();
  const [sending, setSending] = useState(false);
  const [failed, setFailed] = useState(false);
  const room = chat.room;
  useEffect(() => {
    let stopped = false;
    room
      .loadComposerDraft(undefined)
      .then((saved) => {
        if (stopped || !saved || !ComposerDraftType.NewMessage.instanceOf(saved.draftType)) return;
        setDraft(saved.plainText);
        setText((typed) => typed || saved.plainText);
      })
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
    // The component has one room for its whole life (key roomId); the SDK replaces the Room object often.
  }, []);
  const peer = chat.face.peer?.userId;
  if (chat.face.isDirect === true && peer !== undefined && ignored.has(peer)) return null;
  const canSend = text.trim() !== '' && !sending;
  const send = async () => {
    setSending(true);
    setFailed(false);
    try {
      const timeline = await room.timeline();
      await timeline.send(contentWithMentions(text.trim(), []));
      setText('');
      setDraft(undefined);
      room.clearComposerDraft(undefined).catch(() => undefined);
      markRead(room).catch(() => undefined);
    } catch (e) {
      console.warn('[trace] tile reply failed', describe(e));
      setFailed(true);
    }
    setSending(false);
  };
  return (
    <form
      data-mural-reply
      className="flex flex-col gap-1 px-3.5 pb-3.5"
      onSubmit={(e) => {
        e.preventDefault();
        if (canSend) void send();
      }}
    >
      {draft && (
        <p className="truncate text-footnote text-ink-secondary">{s('bipe_app_chat_list_draft', draft)}</p>
      )}
      {failed && <p className="text-footnote text-trust-danger-ink">{s('bipe_error_generic')}</p>}
      {companyName && <p className="text-caption text-ink-secondary">{s('bipe_company_replying_as', companyName)}</p>}
      <div className="flex items-end gap-2">
        <div className="bipe-glass flex min-w-0 flex-1 rounded-[22px] pr-1 pl-4">
          <input
            type="text"
            className="min-h-11 w-full bg-transparent text-body text-ink-primary outline-none placeholder:text-ink-disabled"
            value={text}
            readOnly={sending}
            placeholder={s('bipe_chats_message_placeholder')}
            aria-label={s('bipe_chats_message_placeholder')}
            onChange={(e) => setText(e.target.value)}
          />
        </div>
        <button
          type="submit"
          className={`grid size-11 shrink-0 place-items-center rounded-full ${canSend ? 'bg-accent-fill text-ink-on-accent hover:bg-accent-fill-pressed' : 'bipe-glass text-ink-disabled'}`}
          disabled={!canSend}
          aria-label={s('bipe_app_timeline_send')}
        >
          <Icon path={paperPlaneRight} size={22} />
        </button>
      </div>
    </form>
  );
}
