import { describe, expect, it } from 'vitest';
import { demoReducer, initialDemoState } from './demoState';

describe('guided project demonstration', () => {
  it('starts paused, keeps navigation bounded, and stops at the final automatic step', () => {
    let state = initialDemoState();
    expect(state.playing).toBe(false);
    expect(demoReducer(state, { type: 'step', step: -1 }).step).toBe(0);
    expect(demoReducer(state, { type: 'step', step: 10 }).step).toBe(3);
    state = demoReducer(state, { type: 'play' });
    for (let i = 0; i < 3; i++) state = demoReducer(state, { type: 'tick' });
    expect(state.step).toBe(3);
    expect(state.playing).toBe(false);
  });
  it('never advances paused content and stops playback on a user interaction', () => {
    const paused = initialDemoState();
    expect(demoReducer(paused, { type: 'tick' })).toEqual(paused);
    const playing = demoReducer(paused, { type: 'play' });
    expect(demoReducer(playing, { type: 'landmark' }).playing).toBe(false);
    expect(demoReducer(playing, { type: 'step', step: 2 }).playing).toBe(false);
  });
  it('clears a previous-language answer when the language changes', () => {
    const answered = demoReducer(initialDemoState(), { type: 'question' });
    const translated = demoReducer(answered, { type: 'language', language: 'ur' });
    expect(translated.language).toBe('ur');
    expect(translated.answerVisible).toBe(false);
  });
  it('keeps Tawaf and Sai independent and requires manual increments up to seven', () => {
    let state = initialDemoState();
    for (let i = 0; i < 10; i++) state = demoReducer(state, { type: 'increment' });
    expect(state.counts.tawaf).toBe(7);
    state = demoReducer(state, { type: 'mode', mode: 'sai' });
    expect(state.counts.sai).toBe(0);
    state = demoReducer(state, { type: 'increment' });
    expect(state.counts).toEqual({ tawaf: 7, sai: 1 });
  });
  it('undoes only the selected counter and resets it without changing the other one', () => {
    let state = demoReducer(initialDemoState(), { type: 'increment' });
    state = demoReducer(state, { type: 'mode', mode: 'sai' });
    state = demoReducer(state, { type: 'increment' });
    state = demoReducer(state, { type: 'undo' });
    expect(state.counts).toEqual({ tawaf: 1, sai: 0 });
    state = demoReducer(state, { type: 'mode', mode: 'tawaf' });
    state = demoReducer(state, { type: 'reset-counter' });
    state = demoReducer(state, { type: 'undo' });
    expect(state.counts.tawaf).toBe(0);
  });
  it('resets the entire demo including its language, answers, counters, and playback', () => {
    let state = demoReducer(initialDemoState(), { type: 'language', language: 'en' });
    state = demoReducer(state, { type: 'increment' });
    state = demoReducer(state, { type: 'question' });
    expect(demoReducer(state, { type: 'reset' })).toEqual(initialDemoState());
  });
});
