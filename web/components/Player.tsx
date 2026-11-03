"use client";

import { useEffect, useRef, useState } from "react";
import { clock, spokenTime } from "@/lib/format";
import { appText } from "@/lib/i18n";
import type { Locale } from "@/lib/messages";
import AiLabel from "./AiLabel";
import { MuteIcon, PauseIcon, PlayIcon, VolumeIcon } from "./Icons";

// Only one take plays at a time across the page.
let playing: HTMLAudioElement | null = null;

/**
 * Accessible audio player (US-023). Native buttons and sliders, so it works with a keyboard and
 * screen readers without custom key handling: Tab to a control, Space or Enter to play or pause,
 * arrow keys to seek by 5 seconds or change the volume (UX-A11Y-03, -06, -14). Nothing plays until
 * the listener presses play, and the AI label is part of the player (REQ-F-33, UX-A11Y-01).
 */
export default function Player({
  src,
  title,
  takeLabel,
  compact = false,
  durationHint,
  locale = "en",
}: {
  src: string;
  title: string;
  takeLabel?: string;
  compact?: boolean;
  durationHint?: number;
  locale?: Locale;
}) {
  const all = appText(locale);
  const t = all.player;
  const audio = useRef<HTMLAudioElement>(null);
  const [isPlaying, setPlaying] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(durationHint ?? 0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [error, setError] = useState(false);
  const name = takeLabel ? t.of(takeLabel, title) : title;

  useEffect(() => {
    const el = audio.current;
    if (!el) return;
    setPlaying(false);
    setTime(0);
    setError(false);
    return () => {
      el.pause();
      if (playing === el) playing = null;
    };
  }, [src]);

  async function toggle() {
    const el = audio.current;
    if (!el) return;
    if (el.paused) {
      if (playing && playing !== el) playing.pause();
      playing = el;
      try {
        await el.play();
      } catch {
        setError(true);
      }
    } else {
      el.pause();
    }
  }

  function seek(value: number) {
    const el = audio.current;
    if (!el) return;
    el.currentTime = value;
    setTime(value);
  }

  const max = duration > 0 ? duration : 1;
  const fill = `${Math.min(100, (time / max) * 100)}%`;

  return (
    <div className={compact ? "player compact" : "player"} role="group" aria-label={t.player(name)}>
      <audio
        ref={audio}
        src={src}
        preload="metadata"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
        onLoadedMetadata={(e) => Number.isFinite(e.currentTarget.duration) && setDuration(e.currentTarget.duration)}
        onDurationChange={(e) => Number.isFinite(e.currentTarget.duration) && setDuration(e.currentTarget.duration)}
        onError={() => setError(true)}
      />
      <div className="player-row">
        <button
          type="button"
          className={compact ? "play-btn sm" : "play-btn"}
          onClick={toggle}
          aria-label={`${isPlaying ? t.pause : t.play}: ${name}`}
        >
          {isPlaying ? <PauseIcon size={compact ? 18 : 24} /> : <PlayIcon size={compact ? 18 : 24} />}
        </button>
        <div className="player-info">
          {!compact && (
            <div className="spread">
              <p className="player-title">{takeLabel ?? title}</p>
              <AiLabel text={all.common.ai} />
            </div>
          )}
          <input
            className="range"
            type="range"
            min={0}
            max={max}
            step={5}
            value={Math.min(time, max)}
            onChange={(e) => seek(Number(e.target.value))}
            aria-label={t.position(name)}
            aria-valuetext={`${spokenTime(time, all.time)} ${all.time.of} ${spokenTime(duration, all.time)}`}
            style={{ ["--fill" as string]: fill }}
          />
          <div className="spread">
            <span className="time" aria-hidden="true">
              {clock(time)} / {clock(duration)}
            </span>
            {compact && takeLabel && <span className="small" style={{ fontWeight: 700 }}>{takeLabel}</span>}
            {!compact && (
              <div className="volume">
                <button
                  type="button"
                  className="btn btn-ghost btn-icon btn-sm"
                  onClick={() => {
                    const next = !muted;
                    setMuted(next);
                    if (audio.current) audio.current.muted = next;
                  }}
                  aria-label={muted ? t.unmute : t.mute}
                  aria-pressed={muted}
                >
                  {muted ? <MuteIcon size={18} /> : <VolumeIcon size={18} />}
                </button>
                <input
                  className="range"
                  type="range"
                  min={0}
                  max={1}
                  step={0.1}
                  value={volume}
                  onChange={(e) => {
                    const v = Number(e.target.value);
                    setVolume(v);
                    if (audio.current) audio.current.volume = v;
                  }}
                  aria-label={t.volume}
                  aria-valuetext={`${Math.round(volume * 100)}%`}
                  style={{ ["--fill" as string]: `${volume * 100}%` }}
                />
              </div>
            )}
          </div>
        </div>
      </div>
      {error && (
        <p className="small" role="alert" style={{ color: "var(--danger)", margin: 0 }}>
          {t.error}
        </p>
      )}
    </div>
  );
}
