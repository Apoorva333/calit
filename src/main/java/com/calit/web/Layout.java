package com.calit.web;

/**
 * Shared inline CSS (all pages) plus the invitee-only timezone-picker bar and reformat
 * script. Owner/admin pages use only {@link #CSS}; the invitee pages additionally use
 * {@link #TZ_BAR}/{@link #TZ_SCRIPT} to relabel times into the viewer's local zone.
 */
public final class Layout {

    private Layout() {}

    public static final String CSS = """
            body{font-family:system-ui,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;color:#222}
            a{color:#2563eb}
            .card{border:1px solid #ddd;border-radius:8px;padding:1rem;margin:.75rem 0}
            .badge{background:#fde68a;border-radius:4px;padding:.1rem .4rem;font-size:.75rem}
            .slot{display:inline-block;margin:.2rem;padding:.4rem .6rem;border:1px solid #2563eb;border-radius:6px}
            nav a{margin-right:1rem}
            label{display:block;margin:.5rem 0}
            input,select{padding:.3rem}
            .err{color:#b91c1c}
            button{padding:.4rem .8rem;cursor:pointer}
            .tz-bar{margin:.5rem 0;font-size:.9rem;color:#444}
            """;

    /**
     * Shared inline vanilla JS reused by every invitee-facing page that shows times
     * (the slot picker, the confirmation page, and the manage/reschedule page).
     *
     * <p>The server renders each time as an absolute instant in a {@code data-utc}
     * attribute (an ISO-8601 instant with offset or {@code Z}); this script reformats
     * every {@code [data-utc]} element into the VIEWER's local timezone (auto-detected,
     * overridable via {@code #tz-picker}). It NEVER changes any submitted value — the
     * booking form's hidden {@code startUtc} input keeps its absolute UTC instant, so the
     * display zone only changes the LABEL, never which instant is booked.</p>
     *
     * <p>The stable marker comment {@code CALIT_TZ_REFORMAT} lets @QuarkusTest assert the
     * script is present without executing it (RestAssured can't run JS).</p>
     */
    public static final String TZ_SCRIPT = """
            <script>
            /* CALIT_TZ_REFORMAT — viewer-local time reformatting (Calendly-standard) */
            (function () {
              var ZONES = [
                'America/Los_Angeles','America/Denver','America/Chicago','America/New_York',
                'America/Sao_Paulo','UTC','Europe/London','Europe/Amsterdam','Europe/Berlin',
                'Europe/Paris','Europe/Madrid','Europe/Athens','Africa/Johannesburg',
                'Asia/Dubai','Asia/Kolkata','Asia/Singapore','Asia/Tokyo',
                'Australia/Sydney','Pacific/Auckland'
              ];
              var detected = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
              if (ZONES.indexOf(detected) < 0) { ZONES.unshift(detected); }

              var picker = document.getElementById('tz-picker');
              var label  = document.getElementById('tz-label');
              if (!picker) { return; }
              ZONES.forEach(function (z) {
                var o = document.createElement('option');
                o.value = z; o.textContent = z;
                if (z === detected) { o.selected = true; }
                picker.appendChild(o);
              });

              function render() {
                var tz = picker.value;
                if (label) { label.textContent = tz; }
                document.querySelectorAll('[data-utc]').forEach(function (el) {
                  var d = new Date(el.dataset.utc);
                  var opts = (el.dataset.timeOnly === '1')
                    ? { timeStyle: 'short', timeZone: tz }
                    : { dateStyle: 'full', timeStyle: 'short', timeZone: tz };
                  el.textContent = d.toLocaleString([], opts);
                });
              }
              picker.addEventListener('change', render);
              render();
            })();
            </script>
            """;

    /** Reusable timezone-picker bar (detected zone selected client-side by TZ_SCRIPT). */
    public static final String TZ_BAR = """
            <div class="tz-bar">
              Times shown in: <strong><span id="tz-label">your local time</span></strong>
              <label style="display:inline">Change:
                <select id="tz-picker"></select>
              </label>
            </div>
            """;
}
