// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Mural home score (ADR 0246, bipe-chat/bipe#1478). The weights are in bipe-shared registries/home-score.json, the
//! reference is tools/lib/home-score.mjs, and test-vectors/home-score.json checks it. Pure: reads only its arguments.

use crate::generated::registries::*;
use std::collections::HashMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HomeChatKind {
    Person,
    Company,
    Group,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HomeReason {
    Mention,
    Unanswered,
}

/// The explicit inputs of one chat. Pass joined, not archived chats only. Step 1 of ADR 0246 passes no model fields.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HomeChat {
    pub id: String,
    pub kind: HomeChatKind,
    pub has_seal: bool,
    pub official: bool,
    pub favourite: bool,
    pub muted: bool,
    pub mentioned: bool,
    pub unread_count: i64,
    pub last_from_other: bool,
    /// None: the time of the latest event is unknown.
    pub minutes_since_activity: Option<i64>,
    pub messages_per_week: i64,
    pub trust_warning: bool,
    pub model_attention: Option<String>,
    pub model_due: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HomeScored {
    pub score_percent: i32,
    pub reason: Option<HomeReason>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HomeTile {
    pub id: String,
    pub score_percent: i32,
    /// None: no tile. The chat is only in the plain list.
    pub tier: Option<u32>,
    pub reason: Option<HomeReason>,
}

/// A score part in integer units of 1/10000.
fn units(x: f64) -> i64 {
    (x * 10000.0 + 0.5).floor() as i64
}

fn model_units(chat: &HomeChat, unread: i64) -> i64 {
    if (chat.kind == HomeChatKind::Company && !chat.has_seal) || chat.official || chat.trust_warning {
        return 0;
    }
    let mut sum = 0;
    if let Some(attention) = chat.model_attention.as_deref()
        && let Some((_, weight)) = HOME_MODEL_ATTENTION.iter().find(|(key, _)| *key == attention)
        && (attention != "none" || unread > 0)
    {
        sum += units(*weight);
    }
    if chat.model_due {
        sum += units(HOME_MODEL_DUE);
    }
    sum.min(units(HOME_MODEL_MAX_WEIGHT))
}

/// The score of one chat and the reason it shows.
#[uniffi::export]
pub fn home_score(chat: HomeChat) -> HomeScored {
    score(&chat)
}

fn score(chat: &HomeChat) -> HomeScored {
    let unread = chat.unread_count.max(0);
    let minutes = chat.minutes_since_activity.map(|m| m.max(0));
    let direct = chat.kind != HomeChatKind::Group;
    let mut sum: i64 = 0;
    if chat.mentioned {
        sum += units(HOME_WEIGHT_MENTION);
    }
    if unread > 0 {
        if direct {
            sum += units(if chat.kind == HomeChatKind::Company && !chat.has_seal {
                HOME_WEIGHT_UNREAD_COMPANY_NO_SEAL
            } else {
                HOME_WEIGHT_UNREAD_DIRECT
            });
        } else {
            sum += units(HOME_UNREAD_GROUP_MAX)
                .min(units(HOME_UNREAD_GROUP_MIN + HOME_UNREAD_GROUP_LOG_SCALE * (1.0 + unread as f64).log10()));
        }
    }
    let unanswered = direct && unread == 0 && chat.last_from_other && minutes.is_some_and(|m| m < HOME_UNANSWERED_MAX_MINUTES);
    if unanswered {
        sum += units(HOME_WEIGHT_UNANSWERED_DIRECT);
    }
    if chat.favourite {
        sum += units(HOME_WEIGHT_FAVOURITE);
    }
    sum += units(HOME_TIE_MAX).min(units(HOME_TIE_LOG_SCALE * (1.0 + chat.messages_per_week.max(0) as f64).log2()));
    if let Some(m) = minutes {
        sum += units(HOME_RECENCY_MAX * (-(m as f64) / HOME_RECENCY_DECAY_MINUTES).exp());
    }
    sum += model_units(chat, unread);
    if chat.muted && !chat.mentioned {
        sum = (sum as f64 * HOME_MUTED_FACTOR + 0.5).floor() as i64;
    }
    if chat.official {
        sum = sum.min(units(HOME_OFFICIAL_CAP));
    }
    let sum = sum.clamp(0, 10000);
    let reason = if chat.mentioned {
        Some(HomeReason::Mention)
    } else if unanswered {
        Some(HomeReason::Unanswered)
    } else {
        None
    };
    HomeScored { score_percent: ((sum + 50) / 100) as i32, reason }
}

/// The tiles in screen order: score descending, then latest activity first (unknown last), then room ID by UTF-16
/// code unit. Each chat takes the first tier it reaches that is not full.
#[uniffi::export]
pub fn home_plan(chats: Vec<HomeChat>) -> Vec<HomeTile> {
    let key = |chat: &HomeChat| chat.minutes_since_activity.map_or(i64::MAX, |m| m.max(0));
    let mut ordered: Vec<(HomeChat, HomeScored)> = chats.into_iter().map(|chat| {
        let scored = score(&chat);
        (chat, scored)
    }).collect();
    ordered.sort_by(|(a, sa), (b, sb)| {
        sb.score_percent
            .cmp(&sa.score_percent)
            .then_with(|| key(a).cmp(&key(b)))
            .then_with(|| a.id.encode_utf16().cmp(b.id.encode_utf16()))
    });
    let mut counts: HashMap<u32, u32> = HashMap::new();
    ordered
        .into_iter()
        .map(|(chat, scored)| {
            let rule = HOME_TIERS.iter().find(|(tier, min, max)| {
                i64::from(scored.score_percent) >= (min * 100.0 + 0.5).floor() as i64
                    && max.is_none_or(|limit| counts.get(tier).copied().unwrap_or(0) < limit)
            });
            if let Some((tier, _, _)) = rule {
                *counts.entry(*tier).or_insert(0) += 1;
            }
            HomeTile { id: chat.id, score_percent: scored.score_percent, tier: rule.map(|(tier, _, _)| *tier), reason: scored.reason }
        })
        .collect()
}
