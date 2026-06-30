"use client";

import { useCallback, useEffect, useState } from "react";
import "./globals.css";

const SUPABASE_URL = "https://pvhntshaadmbmpskwqmg.supabase.co";
const ANON_KEY =
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InB2aG50c2hhYWRtYm1wc2t3cW1nIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Mzg3MzQ0MzUsImV4cCI6MjA1NDMxMDQzNX0.Bxi5dVLrb_t_Y7NdT34If3A0FigTopUWSuT9rdcHzYw";
const PIN = "2573";

const STATUS = {
  pending: { label: "대기", color: "#9E9E9E" },
  uploading: { label: "전송중", color: "#1976D2" },
  success: { label: "완료", color: "#388E3C" },
  failed: { label: "실패", color: "#D32F2F" },
};

function fmtDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function fmtFull(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}년 ${p(d.getMonth() + 1)}월 ${p(d.getDate())}일 ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export default function Page() {
  const [unlocked, setUnlocked] = useState(false);
  const [pinInput, setPinInput] = useState("");
  const [pinMsg, setPinMsg] = useState("");

  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [zoom, setZoom] = useState(null);

  useEffect(() => {
    if (typeof window !== "undefined" && localStorage.getItem("cp_pin") === PIN) {
      setUnlocked(true);
    }
  }, []);

  const fetchLogs = useCallback(async () => {
    try {
      const res = await fetch(
        `${SUPABASE_URL}/rest/v1/camera_print_logs?select=*&order=created_at.desc&limit=300`,
        { headers: { apikey: ANON_KEY, Authorization: `Bearer ${ANON_KEY}` }, cache: "no-store" }
      );
      if (!res.ok) throw new Error(`서버 오류 ${res.status}`);
      const data = await res.json();
      setLogs(data);
      setError("");
    } catch (e) {
      setError(e.message || "불러오기 실패");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!unlocked) return;
    fetchLogs();
    const t = setInterval(fetchLogs, 5000);
    return () => clearInterval(t);
  }, [unlocked, fetchLogs]);

  function submitPin(e) {
    e.preventDefault();
    if (pinInput === PIN) {
      localStorage.setItem("cp_pin", PIN);
      setUnlocked(true);
    } else {
      setPinMsg("PIN이 올바르지 않습니다");
      setPinInput("");
    }
  }

  if (!unlocked) {
    return (
      <div className="gate">
        <h2>🔒 카메라 프린트 기록</h2>
        <form onSubmit={submitPin} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 14 }}>
          <input
            type="password"
            inputMode="numeric"
            placeholder="PIN"
            value={pinInput}
            onChange={(e) => setPinInput(e.target.value)}
            autoFocus
          />
          <div className="msg">{pinMsg}</div>
          <button className="btn" type="submit">열기</button>
        </form>
      </div>
    );
  }

  const filtered = logs.filter((l) => {
    if (statusFilter !== "all" && l.status !== statusFilter) return false;
    if (query && !(l.file_name || "").toLowerCase().includes(query.toLowerCase())) return false;
    return true;
  });

  return (
    <div className="wrap">
      <div className="header">
        <h1>🖨️ 프린트 기록</h1>
        <span className="count">{loading ? "불러오는 중…" : `총 ${logs.length}건`}</span>
      </div>

      <div className="toolbar">
        <input
          type="text"
          placeholder="파일명 검색"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
          <option value="all">전체</option>
          <option value="success">완료</option>
          <option value="failed">실패</option>
          <option value="uploading">전송중</option>
          <option value="pending">대기</option>
        </select>
        <button className="btn" onClick={fetchLogs}>새로고침</button>
      </div>

      {error && <div className="err" style={{ marginBottom: 10 }}>⚠️ {error}</div>}

      {!loading && filtered.length === 0 && (
        <div className="empty">기록이 없습니다</div>
      )}

      {filtered.map((l) => {
        const st = STATUS[l.status] || STATUS.pending;
        const clickable = !!l.image_url;
        return (
          <div
            className="card"
            key={l.id}
            style={{ cursor: clickable ? "zoom-in" : "default" }}
            onClick={() => clickable && setZoom(l.image_url)}
          >
            {l.image_url ? (
              <img className="thumb" src={l.image_url} alt="" />
            ) : (
              <div className="thumb">🖼️</div>
            )}
            <div className="info">
              <div className="row1">
                <span className="badge" style={{ background: st.color }}>{st.label}</span>
              </div>
              <div className="fname">{fmtFull(l.created_at)}</div>
              {l.status === "failed" && l.error_message && (
                <div className="err">{l.error_message}</div>
              )}
            </div>
          </div>
        );
      })}

      {zoom && (
        <div className="lightbox" onClick={() => setZoom(null)}>
          <img src={zoom} alt="원본" />
        </div>
      )}
    </div>
  );
}
