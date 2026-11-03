import { notFound } from "next/navigation";
import AiLabel from "@/components/AiLabel";
import AppShell from "@/components/AppShell";
import { ShieldIcon } from "@/components/Icons";
import { dateTime } from "@/lib/format";
import { appText } from "@/lib/i18n";
import { isLocale } from "@/lib/messages";
import PrintButton from "./PrintButton";

export const metadata = { title: "Certificate · BeatMind AI", robots: { index: false } };
export const dynamic = "force-dynamic";

type Certificate = {
  id: string;
  scope: "commercial_free" | "ownership_paid";
  status: "valid" | "revoked";
  title: string;
  titleLatin: string | null;
  languageTag: string;
  created: string;
  createdAt: string;
  planAtCreation: string;
  accountReference: string;
  grant: string;
  aiDisclosure: string;
  humanInputs: string[];
  masterSha256: string | null;
  issuedAt: string;
  revokedAt: string | null;
  attribution: string | null;
  conditions: string[];
  notice: string;
};

const ID = /^BM-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/;

async function load(id: string): Promise<Certificate | null> {
  const origin = process.env.API_ORIGIN ?? "http://localhost:8080";
  try {
    const r = await fetch(`${origin}/v1/certificates/${id}`, { cache: "no-store" });
    return r.ok ? await r.json() : null;
  } catch {
    return null;
  }
}

/**
 * Public certificate page (US-025, REQ-F-22, API-012): anyone with the ID, such as a platform
 * reviewing a claim, can check what BeatMind granted. It shows an account reference, never the
 * holder's email. The legal wording comes from the API in English, the binding language; labels
 * follow the page language. Prints to one A4 page (print-to-PDF stands in for a PDF file).
 */
export default async function CertificatePage({ params }: { params: Promise<{ locale: string; id: string }> }) {
  const { locale, id } = await params;
  if (!isLocale(locale)) notFound();
  const t = appText(locale);
  const C = t.certificate;
  const cert = ID.test(id) ? await load(id) : null;

  return (
    <AppShell locale={locale}>
      {cert === null ? (
        <div className="empty">
          <h1>{C.title}</h1>
          <p role="alert">{C.notFound}</p>
        </div>
      ) : (
        <article className="card certificate stack" aria-labelledby="cert-heading" lang="en">
          <header className="spread">
            <div>
              <p className="eyebrow" lang={locale} style={{ margin: 0 }}>
                {cert.scope === "commercial_free" ? C.commercial : C.ownership}
              </p>
              <h1 id="cert-heading" lang={locale} style={{ margin: 0 }}>
                {C.title}
              </h1>
            </div>
            <span className={cert.status === "valid" ? "badge badge-success" : "badge badge-danger"} lang={locale}>
              <ShieldIcon size={14} /> {cert.status === "valid" ? C.valid : C.revoked}
            </span>
          </header>
          <dl className="facts">
            <dt lang={locale}>{C.id}</dt>
            <dd>
              <code>{cert.id}</code>
            </dd>
            <dt lang={locale}>{C.song}</dt>
            <dd>
              <span lang={cert.languageTag}>{cert.title}</span>
              {cert.titleLatin && cert.titleLatin !== cert.title && <span className="muted"> ({cert.titleLatin})</span>}
            </dd>
            <dt lang={locale}>{C.language}</dt>
            <dd lang={locale}>{t.languageByTag[cert.languageTag] ?? cert.languageTag}</dd>
            <dt lang={locale}>{C.created}</dt>
            <dd>{cert.created}</dd>
            <dt lang={locale}>{C.holder}</dt>
            <dd>{cert.accountReference}</dd>
            <dt lang={locale}>{C.plan}</dt>
            <dd lang={locale}>{t.plans[cert.planAtCreation] ?? cert.planAtCreation}</dd>
            <dt lang={locale}>{C.issued}</dt>
            <dd lang={locale}>{dateTime(cert.issuedAt, t.intlLocale)}</dd>
            {cert.masterSha256 && (
              <>
                <dt lang={locale}>{C.sha}</dt>
                <dd>
                  <code className="small">{cert.masterSha256}</code>
                </dd>
              </>
            )}
          </dl>
          <section className="stack">
            <h2 lang={locale}>{C.grant}</h2>
            <p>{cert.grant}</p>
          </section>
          <section className="stack">
            <h2 lang={locale}>{C.conditions}</h2>
            <ul>
              {cert.conditions.map((c) => (
                <li key={c}>{c}</li>
              ))}
            </ul>
            {cert.attribution && (
              <p>
                <strong lang={locale}>{C.attribution}:</strong> {cert.attribution}
              </p>
            )}
          </section>
          <section className="stack">
            <h2 lang={locale}>{C.ai}</h2>
            <p className="cluster">
              <AiLabel text={t.common.ai} /> {cert.aiDisclosure}
            </p>
            <h3 lang={locale}>{C.human}</h3>
            <ul>
              {cert.humanInputs.map((h) => (
                <li key={h}>{h}</li>
              ))}
            </ul>
          </section>
          <p className="notice small">{cert.notice}</p>
          <div className="no-print" lang={locale}>
            <PrintButton label={C.print} />
          </div>
        </article>
      )}
    </AppShell>
  );
}
