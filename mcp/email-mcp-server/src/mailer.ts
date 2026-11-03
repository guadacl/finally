import nodemailer from "nodemailer";

/** What the send_email tool needs from a mail transport (nodemailer's Transporter satisfies it; tests mock it). */
export interface MailTransport {
  sendMail(message: {
    from: string;
    to: string;
    subject: string;
    text: string;
    html?: string;
  }): Promise<{ messageId?: string; accepted?: unknown[]; rejected?: unknown[] }>;
}

export interface MailConfig {
  host: string;
  port: number;
  username?: string;
  password?: string;
  from: string;
  startTls: boolean;
  /** Implicit TLS (port 465). */
  secure: boolean;
}

function flag(value: string | undefined, fallback = false): boolean {
  if (value === undefined || value.trim() === "") return fallback;
  return ["1", "true", "yes", "on"].includes(value.trim().toLowerCase());
}

/** Reads MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD / MAIL_FROM / MAIL_STARTTLS / MAIL_SECURE. */
export function mailConfigFromEnv(env: NodeJS.ProcessEnv = process.env): MailConfig {
  const port = Number.parseInt(env.MAIL_PORT?.trim() || "1025", 10);
  return {
    host: env.MAIL_HOST?.trim() || "localhost",
    port: Number.isFinite(port) ? port : 1025,
    username: env.MAIL_USERNAME?.trim() || undefined,
    password: env.MAIL_PASSWORD || undefined,
    from: env.MAIL_FROM?.trim() || "FinAlly <alerts@finally.local>",
    startTls: flag(env.MAIL_STARTTLS),
    secure: flag(env.MAIL_SECURE, port === 465),
  };
}

export function createSmtpTransport(config: MailConfig): MailTransport {
  return nodemailer.createTransport({
    host: config.host,
    port: config.port,
    secure: config.secure,
    requireTLS: config.startTls,
    ignoreTLS: !config.startTls && !config.secure,
    auth: config.username ? { user: config.username, pass: config.password ?? "" } : undefined,
    connectionTimeout: 10_000,
    greetingTimeout: 10_000,
    socketTimeout: 15_000,
  });
}

/** Host without credentials, for logs. */
export function describe(config: MailConfig): string {
  return `${config.host}:${config.port}${config.startTls ? " (STARTTLS)" : config.secure ? " (TLS)" : ""}`;
}
