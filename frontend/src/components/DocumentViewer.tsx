import { useEffect, useRef, useState } from 'react';
import { LeftOutlined, RightOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { Alert, Button, Skeleton, Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { getDocument, GlobalWorkerOptions, type PDFDocumentLoadingTask, type PDFDocumentProxy } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { ApiError } from '../api/client';
import { useErrorMessage } from './errorMessage';

GlobalWorkerOptions.workerSrc = workerUrl;

interface Props {
  /** Loads the PDF rendition with the user's token; the browser never opens the file on its own. */
  load: () => Promise<Blob>;
  /** Shown in the header bar. */
  fileName?: string;
  /** Repeated across the page for classified documents: a deterrent, not a control. */
  watermark?: string;
  /** Narrower page, for the task pane. */
  compact?: boolean;
}

const ZOOM_STEP = 0.25;
const WATERMARK_LINES = 18;

/**
 * The PDF preview, rendered by PDF.js onto a canvas (no plug-in, and PDF.js runs no scripts from the document), in a
 * header bar and a sunken well (UI handoff §4.11). The page fits the well; zoom scales from there.
 */
export function DocumentViewer({ load, fileName, watermark, compact = false }: Props) {
  const { t } = useTranslation();
  const errorMessage = useErrorMessage();
  const canvas = useRef<HTMLCanvasElement>(null);
  const well = useRef<HTMLDivElement>(null);
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null);
  const [page, setPage] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [error, setError] = useState<unknown>(null);
  /** An image original (PNG, JPEG, GIF, WebP) is shown as itself; PDF.js renders only PDFs. */
  const [image, setImage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let task: PDFDocumentLoadingTask | null = null;
    let objectUrl: string | null = null;
    load()
      .then(async (blob) => {
        if (blob.type.startsWith('image/')) {
          objectUrl = URL.createObjectURL(blob);
          if (!cancelled) setImage(objectUrl);
          return;
        }
        task = getDocument({ data: await blob.arrayBuffer() });
        const document = await task.promise;
        if (!cancelled) {
          setPdf(document);
          setPage(1);
        }
      })
      .catch((failure: unknown) => {
        if (!cancelled) setError(failure);
      });
    return () => {
      cancelled = true;
      void task?.destroy();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [load]);

  useEffect(() => {
    if (!pdf) return;
    let cancelled = false;
    let task: { cancel: () => void } | null = null;
    void pdf.getPage(page).then((pdfPage) => {
      const target = canvas.current;
      if (!target || cancelled) return;
      const natural = pdfPage.getViewport({ scale: 1 });
      const room = Math.min((well.current?.clientWidth ?? 0) - 48, compact ? 440 : 760);
      const fit = room > 0 ? room / natural.width : 1.2;
      const ratio = window.devicePixelRatio || 1;
      const viewport = pdfPage.getViewport({ scale: fit * zoom * ratio });
      target.width = Math.floor(viewport.width);
      target.height = Math.floor(viewport.height);
      target.style.inlineSize = `${Math.floor(viewport.width / ratio)}px`;
      const render = pdfPage.render({ canvas: target, viewport });
      task = render;
      render.promise.catch(() => undefined);
    });
    return () => {
      cancelled = true;
      task?.cancel();
    };
  }, [pdf, page, zoom, compact]);

  if (error) {
    // the server's refusal in its own words; anything else is a file PDF.js could not read
    return <Alert type="error" showIcon title={error instanceof ApiError ? errorMessage(error) : t('viewer.failed')} />;
  }
  const pages = pdf?.numPages ?? 1;
  return (
    <figure className={`jdms-viewer${compact ? ' jdms-viewer-compact' : ''}`}>
      <div className="jdms-viewer-bar">
        <span className="jdms-viewer-name">
          {fileName ? <bdi>{fileName}</bdi> : null}
          {pdf ? <span aria-live="polite">{t('viewer.page', { page, pages })}</span> : null}
        </span>
        {pdf || image ? (
          <span className="jdms-viewer-tools">
            {pages > 1 ? (
              <>
                <Tooltip title={t('viewer.previous')}>
                  <Button size="small" aria-label={t('viewer.previous')} icon={<LeftOutlined aria-hidden className="jdms-flip-rtl" />}
                    disabled={page <= 1} onClick={() => setPage((current) => current - 1)} />
                </Tooltip>
                <Tooltip title={t('viewer.next')}>
                  <Button size="small" aria-label={t('viewer.next')} icon={<RightOutlined aria-hidden className="jdms-flip-rtl" />}
                    disabled={page >= pages} onClick={() => setPage((current) => current + 1)} />
                </Tooltip>
              </>
            ) : null}
            <Tooltip title={t('viewer.zoomOut')}>
              <Button size="small" aria-label={t('viewer.zoomOut')} icon={<ZoomOutOutlined aria-hidden  />} disabled={zoom <= 0.5}
                onClick={() => setZoom((current) => Math.max(0.5, current - ZOOM_STEP))} />
            </Tooltip>
            <Tooltip title={t('viewer.zoomIn')}>
              <Button size="small" aria-label={t('viewer.zoomIn')} icon={<ZoomInOutlined aria-hidden  />} disabled={zoom >= 3}
                onClick={() => setZoom((current) => Math.min(3, current + ZOOM_STEP))} />
            </Tooltip>
          </span>
        ) : null}
      </div>
      <div ref={well} className="jdms-viewer-well">
        {image ? (
          <div className="jdms-viewer-page">
            <img src={image} alt={t('viewer.image')} className="jdms-viewer-image" data-testid="image-preview"
              style={{ inlineSize: `${Math.round(zoom * 100)}%` }} />
            {watermark ? (
              <div className="jdms-watermark" aria-hidden="true">
                <div className="jdms-watermark-tile">
                  {Array.from({ length: WATERMARK_LINES }, (_, line) => (
                    <span key={line}>{`${watermark}     ${watermark}     ${watermark}`}</span>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        ) : pdf ? (
          <div className="jdms-viewer-page">
            {/* a canvas inherits the page direction, and PDF.js places glyphs assuming left to right */}
            <canvas ref={canvas} dir="ltr" role="img" aria-label={t('viewer.canvas', { page })} data-testid="pdf-canvas" />
            {watermark ? (
              <div className="jdms-watermark" aria-hidden="true">
                <div className="jdms-watermark-tile">
                  {Array.from({ length: WATERMARK_LINES }, (_, line) => (
                    <span key={line}>{`${watermark}     ${watermark}     ${watermark}`}</span>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        ) : (
          <Skeleton.Image active className="jdms-viewer-skeleton" />
        )}
      </div>
      {watermark ? <figcaption className="jdms-viewer-caption">{t('viewer.watermarked')}</figcaption> : null}
    </figure>
  );
}
