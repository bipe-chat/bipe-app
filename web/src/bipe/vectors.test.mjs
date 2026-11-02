// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The bipe-shared test vectors against the web copy of the reference rules (bipe-chat/bipe#901, ADR 0031 rule 4),
// like SharedVectorTests.swift and SharedVectorTest.kt. Run: node --test src/bipe/ (in web/). BIPE_SHARED points at
// bipe-shared; the default is the folder next to bipe-app.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import * as rules from "./generated/lib/registries.mjs";
import { cvmLines } from "./generated/lib/cvm.mjs";
import { noticeLinkClickable } from "./generated/lib/notice.mjs";
import { pixCheck } from "./generated/lib/pix.mjs";
import { ptBR, en, t } from "./generated/strings.ts";

const here = dirname(fileURLToPath(import.meta.url));
const shared = process.env.BIPE_SHARED ?? join(here, "../../../../bipe-shared");
const vectors = (file) =>
  JSON.parse(readFileSync(join(shared, "test-vectors", file), "utf8"));
const reg = JSON.parse(
  readFileSync(join(here, "generated/registries.json"), "utf8"),
);

const reservedCategory = new Map(
  Object.entries(reg.reserved.categories).flatMap(([cat, names]) =>
    names.map((n) => [n, cat]),
  ),
);

test("normalization.json", () => {
  for (const { input, normalized, reserved } of vectors("normalization.json")
    .vectors) {
    const got = rules.normalize(input, reg.homoglyphs.map);
    assert.equal(got, normalized, input);
    assert.equal(reservedCategory.get(got) ?? null, reserved, input);
  }
});

test("scam-detection.json", () => {
  for (const { message, matches } of vectors("scam-detection.json").vectors)
    assert.deepEqual(rules.scamMatchIDs(message, reg), matches, message);
});

test("scam-warnings.json", () => {
  for (const { message, kind } of vectors("scam-warnings.json").vectors)
    assert.equal(rules.scamWarningKind(message, reg), kind, message);
});

test("handles.json", () => {
  for (const c of vectors("handles.json").cases) {
    assert.equal(
      rules.handleProblem(c.input, reg, c.claimant),
      c.problem,
      `${c.input} ${c.claimant ?? ""}`,
    );
    assert.equal(rules.handleSkeleton(c.input, reg), c.skeleton, c.input);
  }
});

test("handle-suggestions.json", () => {
  for (const { name, suggestions } of vectors("handle-suggestions.json").cases)
    assert.deepEqual(rules.handleSuggestions(name, reg), suggestions, name);
});

test("invite-codes.json", () => {
  for (const { input, code } of vectors("invite-codes.json").vectors)
    assert.equal(rules.extractInviteCode(input, reg), code, input);
});

test("cnpj.json", () => {
  for (const { input, cnpj } of vectors("cnpj.json").vectors)
    assert.equal(rules.normalizeCnpj(input), cnpj, input);
});

test("phone-numbers.json", () => {
  const file = vectors("phone-numbers.json");
  for (const { input, e164 } of file.vectors)
    assert.equal(rules.normalizePhone(input, reg), e164, input);
  for (const { e164, display } of file.display)
    assert.equal(rules.displayPhone(e164, reg), display, e164);
});

test("bipe-servers.json", () => {
  for (const { userId, bipe } of vectors("bipe-servers.json").vectors)
    assert.equal(rules.isBipeUserID(userId, reg), bipe, userId);
});

test("pins.json", () => {
  for (const { input, problem } of vectors("pins.json").cases)
    assert.equal(rules.pinProblem(input, reg), problem, input);
});

test("remote-banner.json", () => {
  const file = vectors("remote-banner.json");
  for (const c of file.state)
    assert.deepEqual(
      rules.remoteBannerState(c.banner, c.app_version, c.now),
      c.expected,
    );
  for (const c of file.compare)
    assert.equal(
      rules.compareAppVersions(c.a, c.b),
      c.expected,
      `${c.a} ${c.b}`,
    );
});

test("onboarding-schedule.json", () => {
  const file = vectors("onboarding-schedule.json");
  for (const c of file.pinReminder) {
    const { step, gapDays } = rules.pinReminderNext(c.step, c.result, reg);
    assert.deepEqual(
      { nextStep: step, gapDays },
      { nextStep: c.nextStep, gapDays: c.gapDays },
      `${c.step} ${c.result}`,
    );
  }
  for (const c of file.bringPeopleReminder)
    assert.deepEqual(
      rules.bringPeopleReminder(c.state, c.nowMs, reg),
      c.expected,
      c.name,
    );
  for (const c of file.chatListPrompt)
    assert.deepEqual(
      rules.chatListPrompt(c.eligible, c.slot, c.nowMs, reg),
      c.expected,
      c.name,
    );
});

test("trust-facts.json", () => {
  for (const v of vectors("trust-facts.json").vectors)
    assert.deepEqual(rules.trustFacts(v.input, reg), v.expected, v.name);
});

test("profile-facts.json", () => {
  const file = vectors("profile-facts.json");
  for (const v of file.vectors)
    assert.deepEqual(
      rules.profileFactLines(v.facts, Date.parse(file.now), reg),
      v.expected,
      v.name,
    );
});

test("pix-check.json", () => {
  for (const { text, ...expected } of vectors("pix-check.json").vectors)
    assert.deepEqual({ ...pixCheck(text, reg) }, expected, text);
});

test("notice-links.json", () => {
  const file = vectors("notice-links.json");
  for (const { name, url, clickable } of file.vectors)
    assert.equal(noticeLinkClickable(file.domains, url), clickable, name);
});

test("cvm-lines.json", () => {
  for (const { name, cvm, ...expected } of vectors("cvm-lines.json").vectors)
    assert.deepEqual({ ...cvmLines(cvm) }, expected, name);
});

test("strings: en has every pt-BR key, and t() fills placeholders", () => {
  assert.deepEqual(Object.keys(en).sort(), Object.keys(ptBR).sort());
  assert.equal(t("bipe_link_computer_ip", ["203.0.113.7"]), "IP: 203.0.113.7");
  assert.equal(
    t("bipe_link_computer_device", ["Chrome"], "en"),
    "Device: Chrome",
  );
});
