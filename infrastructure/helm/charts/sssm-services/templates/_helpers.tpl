{{/*
Expand the name of the chart.
*/}}
{{- define "sssm.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "sssm.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- printf "%s" $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "sssm.labels" -}}
helm.sh/chart: {{ include "sssm.name" . }}-{{ .Chart.Version }}
{{ include "sssm.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "sssm.selectorLabels" -}}
app.kubernetes.io/name: {{ include "sssm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app: {{ include "sssm.name" . }}
{{- end }}
