/**
 * RoomOpeningsEditor — per-wall openings editor with quick entry.
 *
 * Renders one group per wall (walls come from the room polygon geometry). Within
 * each wall the user can add openings, choosing a `type` (DOOR/WINDOW) and typing
 * a `count`, per-unit `height`, and `width`. Openings can be removed individually.
 *
 * Controlled component: the current `walls` array is passed in and every mutation
 * is reported through `onChange`, so the parent form (RoomFormSheet) owns state and
 * can validate/submit it. Values are kept as strings while typing (numeric coercion
 * happens at the zod layer on submit) so partially-typed input is not clobbered.
 */
import { useTranslation } from 'react-i18next'
import { Plus, Trash2 } from 'lucide-react'

import { NumberInput } from '@/components/ui/number-input'
import type { OpeningType } from '../types'

/** A wall as edited in the form: gaps + a list of openings (values as strings). */
export interface EditorOpening {
  type: OpeningType
  count: string
  height: string
  width: string
}

export interface EditorWall {
  wallGap: string
  finishGap: string
  openings: EditorOpening[]
}

interface RoomOpeningsEditorProps {
  walls: EditorWall[]
  onChange: (walls: EditorWall[]) => void
  disabled?: boolean
}

const emptyWallForIndex: EditorWall = { wallGap: '', finishGap: '', openings: [] }

const emptyOpening = (): EditorOpening => ({
  type: 'DOOR',
  count: '1',
  height: '',
  width: '',
})

const emptyWall = (): EditorWall => ({ wallGap: '', finishGap: '', openings: [] })

export function RoomOpeningsEditor({
  walls,
  onChange,
  disabled,
}: Readonly<RoomOpeningsEditorProps>) {
  const { t } = useTranslation()

  const updateWall = (wallIndex: number, next: Partial<EditorWall>) => {
    onChange(walls.map((w, i) => (i === wallIndex ? { ...w, ...next } : w)))
  }

  const addWall = () => onChange([...walls, emptyWall()])

  const removeWall = (wallIndex: number) => {
    onChange(walls.filter((_, i) => i !== wallIndex))
  }

  const addOpening = (wallIndex: number) => {
    const wall = walls[wallIndex] ?? emptyWallForIndex
    updateWall(wallIndex, { openings: [...wall.openings, emptyOpening()] })
  }

  const updateOpening = (
    wallIndex: number,
    openingIndex: number,
    next: Partial<EditorOpening>,
  ) => {
    const wall = walls[wallIndex] ?? emptyWallForIndex
    updateWall(wallIndex, {
      openings: wall.openings.map((o, i) =>
        i === openingIndex ? { ...o, ...next } : o,
      ),
    })
  }

  const removeOpening = (wallIndex: number, openingIndex: number) => {
    const wall = walls[wallIndex] ?? emptyWallForIndex
    updateWall(wallIndex, {
      openings: wall.openings.filter((_, i) => i !== openingIndex),
    })
  }

  const inputClass =
    'h-9 w-full rounded-md border border-border bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50'

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-foreground">
          {t('rooms.form.openings')}
        </span>
        <button
          type="button"
          onClick={addWall}
          disabled={disabled}
          className="inline-flex h-8 items-center gap-1 rounded-md border border-border bg-background px-2 text-xs font-medium text-foreground transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Plus className="h-3.5 w-3.5" />
          {t('rooms.form.addWall')}
        </button>
      </div>

      {walls.length === 0 ? (
        <p className="text-xs text-muted-foreground">{t('rooms.form.noWalls')}</p>
      ) : (
        <div className="space-y-4">
          {walls.map((wall, wallIndex) => (
            <div
              // Walls are a positional list with no stable id; the index is the key.
              // eslint-disable-next-line react/no-array-index-key
              key={wallIndex}
              className="space-y-3 rounded-md border border-border p-3"
              data-testid={`room-wall-${wallIndex}`}
            >
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  {t('rooms.form.wall', { index: wallIndex + 1 })}
                </span>
                <button
                  type="button"
                  onClick={() => removeWall(wallIndex)}
                  disabled={disabled}
                  aria-label={t('rooms.form.removeWall')}
                  className="inline-flex h-7 w-7 items-center justify-center rounded-md text-destructive transition-colors hover:bg-destructive/10 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>

              {/* Per-wall gaps */}
              <div className="grid grid-cols-2 gap-2">
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    {t('rooms.form.wallGap')}
                  </label>
                  <NumberInput
                    value={wall.wallGap}
                    disabled={disabled}
                    onChange={(next) => updateWall(wallIndex, { wallGap: next })}
                    className={inputClass}
                  />
                </div>
                <div className="space-y-1">
                  <label className="text-xs text-muted-foreground">
                    {t('rooms.form.finishGap')}
                  </label>
                  <NumberInput
                    value={wall.finishGap}
                    disabled={disabled}
                    onChange={(next) => updateWall(wallIndex, { finishGap: next })}
                    className={inputClass}
                  />
                </div>
              </div>

              {/* Openings on this wall */}
              <div className="space-y-2">
                {wall.openings.map((opening, openingIndex) => (
                  <div
                    // Openings are a positional list with no stable id.
                    // eslint-disable-next-line react/no-array-index-key
                    key={openingIndex}
                    className="grid grid-cols-[1fr_auto] gap-2"
                    data-testid={`room-opening-${wallIndex}-${openingIndex}`}
                  >
                    <div className="grid grid-cols-4 gap-2">
                      <select
                        aria-label={t('rooms.form.openingType')}
                        value={opening.type}
                        disabled={disabled}
                        onChange={(e) =>
                          updateOpening(wallIndex, openingIndex, {
                            type: e.target.value as OpeningType,
                          })
                        }
                        className={inputClass}
                      >
                        <option value="DOOR">{t('rooms.openingType.DOOR')}</option>
                        <option value="WINDOW">{t('rooms.openingType.WINDOW')}</option>
                      </select>
                      <input
                        type="number"
                        step="1"
                        min="1"
                        aria-label={t('rooms.form.openingCount')}
                        placeholder={t('rooms.form.openingCount')}
                        value={opening.count}
                        disabled={disabled}
                        onChange={(e) =>
                          updateOpening(wallIndex, openingIndex, { count: e.target.value })
                        }
                        className={inputClass}
                      />
                      <NumberInput
                        aria-label={t('rooms.form.openingHeight')}
                        placeholder={t('rooms.form.openingHeight')}
                        value={opening.height}
                        disabled={disabled}
                        onChange={(next) =>
                          updateOpening(wallIndex, openingIndex, { height: next })
                        }
                        className={inputClass}
                      />
                      <NumberInput
                        aria-label={t('rooms.form.openingWidth')}
                        placeholder={t('rooms.form.openingWidth')}
                        value={opening.width}
                        disabled={disabled}
                        onChange={(next) =>
                          updateOpening(wallIndex, openingIndex, { width: next })
                        }
                        className={inputClass}
                      />
                    </div>
                    <button
                      type="button"
                      onClick={() => removeOpening(wallIndex, openingIndex)}
                      disabled={disabled}
                      aria-label={t('rooms.form.removeOpening')}
                      className="inline-flex h-9 w-9 items-center justify-center rounded-md text-destructive transition-colors hover:bg-destructive/10 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                ))}

                <button
                  type="button"
                  onClick={() => addOpening(wallIndex)}
                  disabled={disabled}
                  className="inline-flex h-8 items-center gap-1 rounded-md border border-dashed border-border bg-background px-2 text-xs font-medium text-foreground transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <Plus className="h-3.5 w-3.5" />
                  {t('rooms.form.addOpening')}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
