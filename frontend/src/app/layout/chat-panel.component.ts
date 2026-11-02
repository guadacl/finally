import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChatService } from '../core/chat.service';
import { PricePipe, QtyPipe } from '../shared/format';

@Component({
  selector: 'app-chat-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, PricePipe, QtyPipe],
  host: { class: 'flex h-full min-h-0' },
  template: `
    @if (collapsed()) {
      <button
        type="button"
        (click)="collapsed.set(false)"
        class="flex w-9 cursor-pointer flex-col items-center gap-2 border-l border-edge bg-panel-2 pt-3 text-muted hover:text-fg"
        title="Open AI assistant"
        data-testid="chat-toggle"
      >
        <span class="text-accent">&#9664;</span>
        <span class="text-[10px] font-semibold tracking-widest uppercase [writing-mode:vertical-rl]">AI Copilot</span>
      </button>
    } @else {
      <aside class="flex w-[340px] min-w-[280px] flex-col border-l border-edge bg-panel-2 xl:w-[380px]">
        <div class="flex items-center justify-between border-b border-edge px-3 py-2">
          <div class="flex items-center gap-2">
            <span class="h-2 w-2 rounded-full bg-accent"></span>
            <span class="text-xs font-semibold tracking-widest uppercase">FinAlly Copilot</span>
          </div>
          <button
            type="button"
            class="cursor-pointer px-1 text-muted hover:text-fg"
            title="Collapse"
            data-testid="chat-toggle"
            (click)="collapsed.set(true)"
          >
            &#9654;
          </button>
        </div>

        <div #scroller class="flex-1 space-y-2 overflow-y-auto px-3 py-3" data-testid="chat-messages">
          @if (!chat.messages().length && !chat.loading()) {
            <div class="mt-6 text-center text-xs leading-relaxed text-muted">
              Ask about your portfolio, request analysis,<br />or tell me to trade — e.g.
              <span class="text-fg">"buy 5 NVDA"</span> or <span class="text-fg">"add PYPL to my watchlist"</span>.
            </div>
          }
          @for (m of chat.messages(); track m.id) {
            <div
              data-testid="chat-message"
              [attr.data-role]="m.role"
              class="flex flex-col"
              [class.items-end]="m.role === 'user'"
            >
              <div
                class="max-w-[92%] rounded-md px-2.5 py-1.5 text-[12.5px] leading-relaxed whitespace-pre-wrap"
                [class]="m.role === 'user' ? 'bg-primary/20 border border-primary/40' : 'bg-panel border border-edge'"
              >
                {{ m.content }}
              </div>
              @if (m.actions; as a) {
                @if (a.trades.length || a.watchlistChanges.length) {
                  <div class="mt-1 flex max-w-[92%] flex-wrap gap-1">
                    @for (t of a.trades; track $index) {
                      <span
                        data-testid="chat-action"
                        [attr.data-status]="t.status"
                        class="num rounded border px-1.5 py-0.5 text-[10.5px]"
                        [class]="t.status === 'executed' ? 'border-up/50 bg-up/10 text-up' : 'border-down/50 bg-down/10 text-down'"
                        [attr.title]="t.error ?? ''"
                      >
                        {{ t.side === 'buy' ? 'BUY' : 'SELL' }} {{ t.quantity | qty }} {{ t.ticker }}
                        @if (t.price != null) {
                          &#64; {{ t.price | price }}
                        }
                        @if (t.status === 'failed') {
                          — {{ t.error || 'failed' }}
                        }
                      </span>
                    }
                    @for (w of a.watchlistChanges; track $index) {
                      <span
                        data-testid="chat-action"
                        [attr.data-status]="w.status"
                        class="rounded border px-1.5 py-0.5 text-[10.5px]"
                        [class]="w.status === 'executed' ? 'border-up/50 bg-up/10 text-up' : 'border-down/50 bg-down/10 text-down'"
                      >
                        {{ w.action === 'add' ? 'WATCH +' : 'WATCH −' }} {{ w.ticker }}
                        @if (w.status === 'failed') {
                          — {{ w.error || 'failed' }}
                        }
                      </span>
                    }
                  </div>
                }
              }
            </div>
          }
          @if (chat.loading()) {
            <div data-testid="chat-loading" class="flex items-center gap-1 px-1 py-1 text-xs text-muted">
              <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-accent"></span>
              <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-accent [animation-delay:120ms]"></span>
              <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-accent [animation-delay:240ms]"></span>
              <span class="ml-1">Thinking…</span>
            </div>
          }
          @if (chat.error(); as err) {
            <div data-testid="chat-error" class="rounded border border-down/50 bg-down/10 px-2 py-1 text-xs text-down">{{ err }}</div>
          }
        </div>

        <form class="flex gap-2 border-t border-edge p-2" (ngSubmit)="send()">
          <input
            name="chatInput"
            data-testid="chat-input"
            class="field min-w-0 flex-1"
            placeholder="Ask FinAlly…"
            autocomplete="off"
            maxlength="4000"
            [ngModel]="draft()"
            (ngModelChange)="draft.set($event)"
          />
          <button
            type="submit"
            data-testid="chat-send"
            class="btn btn-submit"
            [disabled]="chat.loading() || !draft().trim()"
          >
            Send
          </button>
        </form>
      </aside>
    }
  `,
})
export class ChatPanelComponent {
  readonly chat = inject(ChatService);
  readonly collapsed = signal(false);
  readonly draft = signal('');
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  constructor() {
    // Keep the conversation scrolled to the bottom.
    effect(() => {
      this.chat.messages();
      this.chat.loading();
      const el = this.scroller()?.nativeElement;
      if (el) queueMicrotask(() => (el.scrollTop = el.scrollHeight));
    });
  }

  send(): void {
    const text = this.draft().trim();
    if (!text || this.chat.loading()) return;
    this.chat.send(text);
    this.draft.set('');
  }
}
