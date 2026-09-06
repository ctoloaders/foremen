import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createMeasurementUnit,
  updateMeasurementUnit,
  deleteMeasurementUnit,
} from './measurement-units-api'
import { measurementUnitKeys } from './query-hooks'
import type {
  MeasurementUnitCreateRequest,
  MeasurementUnitUpdateRequest,
} from '../types'

export function useCreateMeasurementUnit() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: MeasurementUnitCreateRequest) => createMeasurementUnit(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: measurementUnitKeys.lists() })
    },
  })
}

export function useUpdateMeasurementUnit() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: MeasurementUnitUpdateRequest }) =>
      updateMeasurementUnit(id, data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: measurementUnitKeys.lists() })
      queryClient.invalidateQueries({ queryKey: measurementUnitKeys.detail(variables.id) })
    },
  })
}

export function useDeleteMeasurementUnit() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteMeasurementUnit(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: measurementUnitKeys.lists() })
    },
  })
}
