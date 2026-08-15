import { invoke } from "@tauri-apps/api/core";
import "@moq/watch/element";
import "./styles.css";

type ConnectionState = "idle" | "paired";

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

interface PairedDevice {
  phoneId: string;
  phoneName: string;
  pairedAt: number;
}

interface ControlStatus {
  connected: boolean;
  phoneId: string | null;
  phoneName: string | null;
  lastSeen: number | null;
  capabilities: Record<string, unknown> | null;
  state: Record<string, unknown> | null;
}

let expiryTimer: number | undefined;
let rotatingPairingCode = false;
let lastControlConnected = false;
let mediaTransportStatus: "offline" | "loading" | "live" = "offline";
let phoneMediaExpected = false;
let pairedDeviceCount: number | null = null;

const requiredElement = (id: string): HTMLElement => {
  const element = document.getElementById(id);
  if (!element) throw new Error(`Missing element #${id}`);
  return element;
};

function renderStatus(status: ReceiverStatus): void {
  const labels: Record<ConnectionState, string> = {
    idle: "Idle",
    paired: "Paired",
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
        ? "Secure pairing confirmed. Start the camera from this phone."
        : "Phone connected to this receiver.";
  } else {
    requiredElement("connection-title").textContent = "No phone connected";
    requiredElement("connection-detail").textContent =
      "Pair LensRelay on your Android phone to begin.";
  }

  requiredElement("show-pairing").textContent =
    (pairedDeviceCount ?? (status.connectionState === "paired" ? 1 : 0)) > 0
      ? "Pair another phone"
      : "Pair a phone";
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
    requiredElement("pairing-progress").style.width = `${(remaining / initialRemaining) * 100}%`;
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

function hidePairingSession(): void {
  requiredElement("pairing-content").hidden = true;
  if (expiryTimer !== undefined) window.clearInterval(expiryTimer);
  expiryTimer = undefined;
  rotatingPairingCode = false;
}

async function createPairingSession(): Promise<void> {
  const button = requiredElement("show-pairing") as HTMLButtonElement;
  const refresh = requiredElement("refresh-pairing") as HTMLButtonElement;
  button.disabled = true;
  refresh.disabled = true;
  try {
    renderPairingSession(await invoke<PairingSession>("create_pairing_session"));
    button.textContent = pairedDeviceCount && pairedDeviceCount > 0 ? "Pair another phone" : "Pair a phone";
  } catch (error) {
    button.textContent = "Could not create code";
    requiredElement("pairing-expiry").textContent = String(error);
  } finally {
    button.disabled = false;
    refresh.disabled = false;
  }
}

function configureMediaPreview(endpoint: string): void {
  const watch = requiredElement("media-watch") as HTMLElementTagNameMap["moq-watch"];
  watch.url = endpoint;
  watch.name = "camera";
  watch.visible = "always";
  watch.muted = true;

  const renderMediaStatus = (status: "offline" | "loading" | "live"): void => {
    mediaTransportStatus = status;
    requiredElement("media-state").textContent = status;
    syncMediaPreviewVisibility();
    if (status === "live" && phoneMediaExpected) {
      requiredElement("status-badge").textContent = "Connected";
      requiredElement("connection-detail").textContent = "Receiving encrypted Media over QUIC video.";
    }
  };
  renderMediaStatus(watch.broadcast.out.status.peek());
  watch.broadcast.out.status.subscribe(renderMediaStatus);
}

function clearMediaPreview(): void {
  const watch = requiredElement("media-watch");
  for (const canvas of watch.querySelectorAll("canvas")) {
    const context = canvas.getContext("2d");
    context?.clearRect(0, 0, canvas.width, canvas.height);
  }
}

function syncMediaPreviewVisibility(): void {
  const visible = phoneMediaExpected && mediaTransportStatus !== "offline";
  requiredElement("live-preview-card").hidden = !visible;
  if (!visible) clearMediaPreview();
}

const asStrings = (value: unknown): string[] => Array.isArray(value) ? value.map(String) : [];
const asNumbers = (value: unknown): number[] => Array.isArray(value) ? value.map(Number).filter(Number.isFinite) : [];

function fillSelect(id: string, values: Array<string | number>, selected: unknown): void {
  const select = requiredElement(id) as HTMLSelectElement;
  const signature = values.join("|");
  if (select.dataset.options !== signature) {
    select.replaceChildren(...values.map((value) => {
      const option = document.createElement("option");
      option.value = String(value);
      option.textContent = String(value);
      return option;
    }));
    select.dataset.options = signature;
  }
  if (selected !== undefined && selected !== null) select.value = String(selected);
}

function renderControlStatus(status: ControlStatus): void {
  const capabilities = status.capabilities ?? {};
  const state = status.state ?? {};
  const commands = new Set(asStrings(capabilities.commands));
  phoneMediaExpected = status.connected && state.streaming === true;
  syncMediaPreviewVisibility();
  lastControlConnected = status.connected;
  requiredElement("control-state").textContent = status.connected ? "Securely connected" : "Phone offline";
  requiredElement("control-detail").textContent = status.connected
    ? `${status.phoneName ?? "Android phone"} is online. Unsupported controls are disabled automatically.`
    : "Open LensRelay on a saved phone to make controls available.";
  requiredElement("control-fields").setAttribute("aria-disabled", String(!status.connected));
  const stream = requiredElement("stream-toggle") as HTMLButtonElement;
  stream.disabled = !status.connected || (!commands.has("start") && !commands.has("stop"));
  stream.textContent = state.streaming === true ? "Stop camera" : "Start camera";

  fillSelect("camera-select", asStrings(capabilities.cameras), state.camera);
  fillSelect("frame-rate-select", asNumbers(capabilities.frameRates), state.frameRate);
  fillSelect("codec-select", asStrings(capabilities.codecs), state.codec);
  fillSelect("white-balance-select", asStrings(capabilities.whiteBalances), state.whiteBalance);
  const resolutions = Array.isArray(capabilities.resolutions)
    ? capabilities.resolutions as Array<{ width: number; height: number }>
    : [];
  fillSelect("resolution-select", resolutions.map(({ width, height }) => `${width}x${height}`),
    state.width && state.height ? `${state.width}x${state.height}` : undefined);

  const ranges: Array<[string, string]> = [
    ["zoom-slider", "zoom"], ["exposure-slider", "exposure"], ["bitrate-slider", "bitrate"],
  ];
  for (const [id, command] of ranges) {
    const input = requiredElement(id) as HTMLInputElement;
    const range = capabilities[command] as { min?: number; max?: number; step?: number } | undefined;
    if (range?.min !== undefined) input.min = String(range.min);
    if (range?.max !== undefined) input.max = String(range.max);
    if (range?.step !== undefined) input.step = String(range.step);
    if (typeof state[command] === "number") input.value = String(state[command]);
    input.disabled = !status.connected || !commands.has(command);
  }
  (requiredElement("camera-select") as HTMLSelectElement).disabled = !status.connected || !commands.has("camera");
  (requiredElement("resolution-select") as HTMLSelectElement).disabled = !status.connected || !commands.has("resolution") || resolutions.length === 0;
  (requiredElement("frame-rate-select") as HTMLSelectElement).disabled = !status.connected || !commands.has("frameRate");
  (requiredElement("codec-select") as HTMLSelectElement).disabled = !status.connected || !commands.has("codec");
  (requiredElement("white-balance-select") as HTMLSelectElement).disabled = !status.connected || !commands.has("whiteBalance");
  for (const [id, command, key] of [
    ["torch-toggle", "torch", "torch"], ["stabilization-toggle", "stabilization", "stabilization"],
  ]) {
    const input = requiredElement(id) as HTMLInputElement;
    input.disabled = !status.connected || !commands.has(command);
    input.checked = state[key] === true;
  }
  requiredElement("zoom-value").textContent = `${Number(state.zoom ?? 1).toFixed(1)}×`;
  requiredElement("exposure-value").textContent = String(state.exposure ?? 0);
  requiredElement("bitrate-value").textContent = `${(Number(state.bitrate ?? 4_000_000) / 1_000_000).toFixed(1)} Mbps`;
}

async function sendControl(command: string, parameters: Record<string, unknown> = {}): Promise<void> {
  const error = requiredElement("control-error");
  error.textContent = "";
  try {
    await invoke("send_control_command", { command, parameters });
    await refreshControlStatus();
  } catch (reason) {
    error.textContent = String(reason);
  }
}

async function refreshControlStatus(): Promise<void> {
  try {
    renderControlStatus(await invoke<ControlStatus>("get_control_status"));
  } catch (error) {
    if (lastControlConnected) requiredElement("control-error").textContent = String(error);
    lastControlConnected = false;
  }
}

function configureControlEvents(): void {
  requiredElement("stream-toggle").addEventListener("click", () => {
    const stopping = (requiredElement("stream-toggle") as HTMLButtonElement).textContent?.startsWith("Stop") === true;
    void sendControl(stopping ? "stop" : "start");
  });
  (requiredElement("camera-select") as HTMLSelectElement).addEventListener("change", (event) =>
    void sendControl("camera", { value: (event.target as HTMLSelectElement).value }));
  (requiredElement("resolution-select") as HTMLSelectElement).addEventListener("change", (event) => {
    const [width, height] = (event.target as HTMLSelectElement).value.split("x").map(Number);
    void sendControl("resolution", { width, height });
  });
  for (const [id, command] of [["frame-rate-select", "frameRate"], ["codec-select", "codec"], ["white-balance-select", "whiteBalance"]]) {
    (requiredElement(id) as HTMLSelectElement).addEventListener("change", (event) => {
      const raw = (event.target as HTMLSelectElement).value;
      void sendControl(command, { value: command === "frameRate" ? Number(raw) : raw });
    });
  }
  for (const [id, command] of [["zoom-slider", "zoom"], ["exposure-slider", "exposure"], ["bitrate-slider", "bitrate"]]) {
    (requiredElement(id) as HTMLInputElement).addEventListener("change", (event) =>
      void sendControl(command, { value: Number((event.target as HTMLInputElement).value) }));
  }
  for (const [id, command] of [["torch-toggle", "torch"], ["stabilization-toggle", "stabilization"]]) {
    (requiredElement(id) as HTMLInputElement).addEventListener("change", (event) =>
      void sendControl(command, { enabled: (event.target as HTMLInputElement).checked }));
  }
  const mirror = requiredElement("mirror-toggle") as HTMLInputElement;
  const rotation = requiredElement("rotation-select") as HTMLSelectElement;
  mirror.checked = localStorage.getItem("lensrelay.mirrorPreview") === "true";
  fillSelect("rotation-select", [0, 90, 180, 270], localStorage.getItem("lensrelay.outputRotation") ?? "0");
  const applyOutputTransform = (): void => {
    const degrees = Number(rotation.value);
    const mirrorScale = mirror.checked ? -1 : 1;
    requiredElement("media-watch").style.transform = `rotate(${degrees}deg) scaleX(${mirrorScale})`;
    localStorage.setItem("lensrelay.mirrorPreview", String(mirror.checked));
    localStorage.setItem("lensrelay.outputRotation", String(degrees));
  };
  mirror.addEventListener("change", applyOutputTransform);
  rotation.addEventListener("change", applyOutputTransform);
  applyOutputTransform();
  requiredElement("media-watch").addEventListener("click", (event) => {
    const rect = requiredElement("media-watch").getBoundingClientRect();
    if (rect.width > 0 && rect.height > 0) {
      void sendControl("focus", {
        x: ((event as MouseEvent).clientX - rect.left) / rect.width,
        y: ((event as MouseEvent).clientY - rect.top) / rect.height,
      });
    }
  });
}

async function initialize(): Promise<void> {
  try {
    const [status, platform, mediaEndpoint] = await Promise.all([
      invoke<ReceiverStatus>("get_receiver_status"),
      invoke<PlatformInfo>("get_platform_info"),
      invoke<string>("get_media_endpoint"),
    ]);
    renderStatus(status);
    renderPlatform(platform);
    configureMediaPreview(mediaEndpoint);
    const devices = await refreshPairedDevices();
    if (devices.length === 0) {
      await createPairingSession();
    }
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

async function forgetPairedDevice(phoneId: string): Promise<void> {
  await invoke("forget_paired_device", { phoneId });
  await Promise.all([refreshPairedDevices(), refreshReceiverStatus()]);
}

async function refreshPairedDevices(): Promise<PairedDevice[]> {
  const devices = await invoke<PairedDevice[]>("get_paired_devices");
  const previousCount = pairedDeviceCount;
  pairedDeviceCount = devices.length;
  const panel = requiredElement("paired-devices-panel");
  const list = requiredElement("paired-devices");
  panel.hidden = devices.length === 0;
  requiredElement("no-paired-devices").hidden = devices.length > 0;
  requiredElement("show-pairing").textContent = devices.length > 0 ? "Pair another phone" : "Pair a phone";
  if (previousCount === 0 && devices.length > 0) hidePairingSession();
  list.replaceChildren();
  for (const device of devices) {
    const row = document.createElement("div");
    row.className = "flex items-center justify-between gap-4 rounded-xl bg-[#18221e] px-4 py-3";
    const identity = document.createElement("div");
    const name = document.createElement("p");
    name.className = "font-semibold";
    name.textContent = device.phoneName;
    const fingerprint = document.createElement("p");
    fingerprint.className = "mt-1 font-mono text-[11px] text-lens-muted";
    fingerprint.textContent = device.phoneId;
    identity.append(name, fingerprint);
    const forget = document.createElement("button");
    forget.type = "button";
    forget.className = "cursor-pointer rounded-lg px-3 py-2 text-sm font-semibold text-[#ffaaa3] hover:bg-[#372522]";
    forget.textContent = "Forget";
    forget.addEventListener("click", () => void forgetPairedDevice(device.phoneId));
    row.append(identity, forget);
    list.append(row);
  }
  return devices;
}

requiredElement("show-pairing").addEventListener("click", () => void createPairingSession());
requiredElement("refresh-pairing").addEventListener("click", () => void createPairingSession());
requiredElement("hide-pairing").addEventListener("click", hidePairingSession);
configureControlEvents();
void initialize();
void refreshControlStatus();
window.setInterval(() => void refreshReceiverStatus(), 1000);
window.setInterval(() => void refreshPairedDevices(), 2000);
window.setInterval(() => void refreshControlStatus(), 1000);
