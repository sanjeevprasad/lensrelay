import { invoke } from "@tauri-apps/api/core";
import "./styles.css";

type ConnectionState = "idle" | "paired" | "discovering" | "connected" | "error";

interface ReceiverStatus {
  connectionState: ConnectionState;
  deviceName: string | null;
  listenAddress: string | null;
}

interface PlatformInfo {
  operatingSystem: string;
  adapterName: string;
  adapterAvailable: boolean;
  detail: string;
}

interface PairingSession {
  payload: string;
  qrSvg: string;
  receiverId: string;
  receiverName: string;
  fingerprint: string;
  expiresAt: number;
}

let expiryTimer: number | undefined;
let rotatingPairingCode = false;

const requiredElement = (id: string): HTMLElement => {
  const element = document.getElementById(id);
  if (!element) throw new Error(`Missing element #${id}`);
  return element;
};

function renderStatus(status: ReceiverStatus): void {
  const labels: Record<ConnectionState, string> = {
    idle: "Idle",
    paired: "Paired",
    discovering: "Discovering",
    connected: "Connected",
    error: "Error",
  };

  requiredElement("status-badge").textContent = labels[status.connectionState];
  requiredElement("receiver-state").textContent =
    status.connectionState === "idle" ? "Waiting for Android" : labels[status.connectionState];
  requiredElement("receiver-address").textContent =
    status.listenAddress ?? "Receiver transport not started";

  if (status.deviceName) {
    requiredElement("connection-title").textContent = status.deviceName;
    requiredElement("connection-detail").textContent =
      status.connectionState === "paired"
        ? "Secure pairing confirmed by this desktop."
        : "Phone connected to this receiver.";
  }

  if (status.connectionState === "paired") {
    if (expiryTimer !== undefined) window.clearInterval(expiryTimer);
    expiryTimer = undefined;
    requiredElement("pairing-content").hidden = true;
    requiredElement("show-pairing").textContent = "Pair another phone";
  }
}

function renderPlatform(info: PlatformInfo): void {
  requiredElement("adapter-name").textContent = info.adapterName;
  requiredElement("adapter-detail").textContent = `${info.operatingSystem} · ${info.detail}`;
}

function renderPairingSession(session: PairingSession): void {
  requiredElement("pairing-content").hidden = false;
  requiredElement("pairing-qr").innerHTML = session.qrSvg;
  requiredElement("pairing-device").textContent = session.receiverName;
  requiredElement("pairing-fingerprint").textContent = session.fingerprint;

  if (expiryTimer !== undefined) window.clearInterval(expiryTimer);
  const initialRemaining = Math.max(1, session.expiresAt - Math.floor(Date.now() / 1000));
  const renderExpiry = (): void => {
    const remaining = Math.max(0, session.expiresAt - Math.floor(Date.now() / 1000));
    const minutes = Math.floor(remaining / 60);
    const seconds = remaining % 60;
    requiredElement("pairing-expiry").textContent =
      remaining > 0
        ? `New code in ${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`
        : "Refreshing pairing code…";
    const progress = requiredElement("pairing-progress");
    progress.style.width = `${(remaining / initialRemaining) * 100}%`;
    requiredElement("pairing-progress-track").setAttribute("aria-valuenow", String(remaining));

    if (remaining === 0 && !rotatingPairingCode) {
      rotatingPairingCode = true;
      if (expiryTimer !== undefined) window.clearInterval(expiryTimer);
      expiryTimer = undefined;
      void createPairingSession().finally(() => {
        rotatingPairingCode = false;
      });
    }
  };
  renderExpiry();
  expiryTimer = window.setInterval(renderExpiry, 1000);
}

async function createPairingSession(): Promise<void> {
  const button = requiredElement("show-pairing") as HTMLButtonElement;
  const refresh = requiredElement("refresh-pairing") as HTMLButtonElement;
  button.disabled = true;
  refresh.disabled = true;
  try {
    renderPairingSession(await invoke<PairingSession>("create_pairing_session"));
    button.textContent = "Pairing code ready";
  } catch (error) {
    button.textContent = "Could not create code";
    requiredElement("pairing-expiry").textContent = String(error);
  } finally {
    button.disabled = false;
    refresh.disabled = false;
  }
}

async function initialize(): Promise<void> {
  try {
    const [status, platform] = await Promise.all([
      invoke<ReceiverStatus>("get_receiver_status"),
      invoke<PlatformInfo>("get_platform_info"),
    ]);
    renderStatus(status);
    renderPlatform(platform);
  } catch (error) {
    requiredElement("receiver-state").textContent = "Desktop service unavailable";
    requiredElement("receiver-address").textContent = String(error);
    requiredElement("status-badge").textContent = "Error";
  }
}

async function refreshReceiverStatus(): Promise<void> {
  try {
    renderStatus(await invoke<ReceiverStatus>("get_receiver_status"));
  } catch {
    // Initialization already surfaces service errors; transient polling failures can retry.
  }
}

requiredElement("show-pairing").addEventListener("click", () => void createPairingSession());
requiredElement("refresh-pairing").addEventListener("click", () => void createPairingSession());
void initialize();
window.setInterval(() => void refreshReceiverStatus(), 1000);
