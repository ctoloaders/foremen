import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import { resolveGoBackTarget } from '@/lib/last-allowed-location'

/**
 * Forbidden_Page (`/403`, FOR-03-07).
 *
 * Rendered inside the authenticated AppShell so a forbidden-but-authenticated
 * user keeps their navigation (Req 5.1). Presents a localized heading and
 * explanatory message (Req 5.2) plus a keyboard-operable Go_Back control
 * (Req 5.3, 8.2). All strings resolve through i18n (`forbidden.*`, Req 7.1).
 */
export default function ForbiddenPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const handleGoBack = () => {
    // Req 5.4/5.5/5.6: resolve at click time to the Last_Allowed_Location, or
    // `/` when there is none or it equals the just-denied route (avoids
    // bouncing the user straight back into the forbidden route).
    navigate(resolveGoBackTarget())
  }

  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4 text-center">
      <h1 className="text-2xl font-semibold text-foreground">
        {t('forbidden.title')}
      </h1>
      <p className="text-muted-foreground">{t('forbidden.message')}</p>
      <Button onClick={handleGoBack}>{t('forbidden.goBack')}</Button>
    </div>
  )
}
