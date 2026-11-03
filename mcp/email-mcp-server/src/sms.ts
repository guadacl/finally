import { randomUUID } from "node:crypto";

/** E.164: '+', country code (no leading 0), 7-15 digits in total. */
export const E164 = /^\+[1-9]\d{6,14}$/;
export const SMS_MAX_LENGTH = 160;

/** At most 160 characters; a cut text ends with "…". */
export function truncateSms(text: string): string {
  return text.length <= SMS_MAX_LENGTH ? text : `${text.slice(0, SMS_MAX_LENGTH - 1)}…`;
}

/** +32470123456 -> +3247*****56 (keeps the country prefix and the last two digits). */
export function maskPhone(phone: string): string {
  if (phone.length <= 7) return phone.replace(/\d(?=\d{2})/g, "*");
  return phone.slice(0, 5) + "*".repeat(phone.length - 7) + phone.slice(-2);
}

export interface SmsResult {
  messageId: string;
  provider: string;
}

/**
 * Where SMS go. Implement this for a real provider (e.g. Brevo, Twilio) and register it in createSmsProvider;
 * the send_sms tool, the app and the workflow stay unchanged.
 */
export interface SmsProvider {
  readonly name: string;
  send(to: string, text: string): Promise<SmsResult>;
}

export interface OutboxEntry {
  id: string;
  to: string; // masked
  text: string;
  provider: string;
  createdAt: string;
}

/** Last N sent SMS (masked numbers), for verification via GET /sms/outbox. Shared by all MCP requests. */
export class SmsOutbox {
  private readonly entries: OutboxEntry[] = [];

  constructor(private readonly capacity = 100) {}

  add(entry: OutboxEntry): void {
    this.entries.push(entry);
    if (this.entries.length > this.capacity) this.entries.splice(0, this.entries.length - this.capacity);
  }

  /** Newest first, copies. */
  list(): OutboxEntry[] {
    return this.entries.slice().reverse().map((e) => ({ ...e }));
  }
}

/** SMS_PROVIDER=log (default): no real SMS, no cost - one log line per message. */
export class LogSmsProvider implements SmsProvider {
  readonly name = "log";

  constructor(private readonly log: (line: string) => void = (l) => console.error(l)) {}

  async send(to: string, text: string): Promise<SmsResult> {
    this.log(`[sms] to ${maskPhone(to)} : ${text}`);
    return { messageId: `log-${randomUUID()}`, provider: this.name };
  }
}

/** A configured provider that is not implemented yet: every send fails with a clear message. */
export class UnsupportedSmsProvider implements SmsProvider {
  constructor(readonly name: string) {}

  async send(): Promise<SmsResult> {
    throw new Error(`SMS_PROVIDER "${this.name}" is not implemented yet (supported: log)`);
  }
}

/** Picks the provider from SMS_PROVIDER. Add real providers here (seam for brevo / twilio). */
export function createSmsProvider(env: NodeJS.ProcessEnv = process.env, log?: (line: string) => void): SmsProvider {
  const name = (env.SMS_PROVIDER ?? "log").trim().toLowerCase() || "log";
  switch (name) {
    case "log":
      return new LogSmsProvider(log);
    // case "brevo": return new BrevoSmsProvider(env.SMS_API_KEY, env.SMS_SENDER);   (not implemented)
    // case "twilio": return new TwilioSmsProvider(env.TWILIO_ACCOUNT_SID, ...);     (not implemented)
    default:
      return new UnsupportedSmsProvider(name);
  }
}
