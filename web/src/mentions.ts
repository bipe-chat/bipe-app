// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Mentions (spec composer, ADR 0141; ComposerMarkdown and MentionDirectory in ComposerSupport.swift). Groups
// only. Typing @ offers the members (and the whole group when the power levels allow); a picked mention is
// "@Name " in the field and becomes a matrix.to link in the Markdown, with Mentions on the event. Received
// mentions are the same links in the HTML body; the bubble draws them as pills.

import { useEffect, useState } from 'react';
import {
  MembershipState,
  MessageFormat,
  messageEventContentFromMarkdown,
  type MessageType as MessageTypeValue,
  type RoomLike as Room,
  type RoomMember,
  type RoomMessageEventContentWithoutRelationLike,
} from './generated/matrix_sdk_ffi';
import { MessageType } from './generated/matrix_sdk_ffi';

export type ComposerMention = { userId?: string; label: string };
export type MentionSuggestion = { userId?: string; name: string; avatarUrl?: string };

const escape = (label: string) => label.replace(/\[/g, '\\[').replace(/]/g, '\\]');

/** Spec composer send: the pills as matrix.to links, then withMentions (room true for the whole group). */
export function contentWithMentions(
  text: string,
  mentions: ComposerMention[],
): RoomMessageEventContentWithoutRelationLike {
  let markdown = text;
  for (const m of mentions) {
    if (!m.userId) continue;
    const at = markdown.indexOf(`@${m.label}`);
    if (at === -1) continue;
    markdown =
      markdown.slice(0, at) +
      `[${escape(m.label)}](https://matrix.to/#/${m.userId})` +
      markdown.slice(at + m.label.length + 1);
  }
  const content = messageEventContentFromMarkdown(markdown);
  const userIds = [...new Set(mentions.flatMap((m) => (m.userId ? [m.userId] : [])))].sort();
  const room = mentions.some((m) => m.userId === undefined);
  return userIds.length || room ? content.withMentions({ userIds, room }) : content;
}

/** The mentions still in the text. */
export const mentionsIn = (mentions: ComposerMention[], text: string) =>
  mentions.filter((m) => text.includes(`@${m.label}`));

/** The @word being typed at the end of the text, without the @. */
export function mentionQuery(text: string): string | undefined {
  const word = text.split(' ').pop() ?? '';
  return word.startsWith('@') ? word.slice(1) : undefined;
}

export function insertMention(text: string, suggestion: MentionSuggestion, everyoneLabel: string) {
  let label = suggestion.userId === undefined ? everyoneLabel : suggestion.name;
  if (label.startsWith('@')) label = label.slice(1);
  const words = text.split(' ');
  words.pop();
  words.push(`@${label} `);
  return { text: words.join(' '), mention: { userId: suggestion.userId, label } };
}

/** The joined members other than the account, and whether the account may mention the whole group. */
export function useMentionDirectory(room: Room, enabled: boolean) {
  const [members, setMembers] = useState<RoomMember[]>([]);
  const [everyone, setEveryone] = useState(false);
  useEffect(() => {
    if (!enabled) return;
    let stopped = false;
    (async () => {
      const iterator = await room.members();
      const all: RoomMember[] = [];
      for (let chunk = iterator.nextChunk(200); chunk; chunk = iterator.nextChunk(200)) all.push(...chunk);
      const own = room.ownUserId();
      const joined = all.filter(
        (m) => MembershipState.Join.instanceOf(m.membership) && m.userId !== own && !m.isIgnored,
      );
      const levels = await room.getPowerLevels().catch(() => undefined);
      if (stopped) return;
      setMembers(joined);
      setEveryone(levels?.canOwnUserTriggerRoomNotification() ?? false);
    })().catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [room, enabled]);
  return (query: string, everyoneLabel: string): MentionSuggestion[] => {
    const needle = query.toLowerCase();
    const out: MentionSuggestion[] = [];
    if (everyone && (needle === '' || everyoneLabel.toLowerCase().includes(needle))) out.push({ name: everyoneLabel });
    const named = members.map((m) => ({ userId: m.userId, name: m.displayName ?? m.userId, avatarUrl: m.avatarUrl }));
    return out.concat(named.filter((m) => needle === '' || m.name.toLowerCase().includes(needle)).slice(0, 8));
  };
}

export type MentionLink = { userId?: string; text: string };

/**
 * The mention links of a received text message: <a href="https://matrix.to/#/@user:server">Name</a> in its
 * HTML body, and the whole-group @room. Read as data only: the page never renders the HTML itself.
 */
export function mentionLinksOf(type: MessageTypeValue): MentionLink[] {
  if (!MessageType.Text.instanceOf(type)) return [];
  const formatted = type.inner.content.formatted;
  if (!formatted || !MessageFormat.Html.instanceOf(formatted.format)) return [];
  const links: MentionLink[] = [];
  const re = /<a\s+href="https:\/\/matrix\.to\/#\/(@[^"/?]+)"[^>]*>([^<]+)<\/a>/g;
  for (let m = re.exec(formatted.body); m; m = re.exec(formatted.body)) {
    links.push({ userId: decodeURIComponent(m[1]), text: decodeEntities(m[2]) });
  }
  return links;
}

function decodeEntities(text: string): string {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&');
}
