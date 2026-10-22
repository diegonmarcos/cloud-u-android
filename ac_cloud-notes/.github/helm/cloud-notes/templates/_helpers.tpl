{{/*
Expand the name of the chart.
*/}}
{{- define "cloud-notes.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "cloud-notes.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "cloud-notes.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "cloud-notes.labels" -}}
helm.sh/chart: {{ include "cloud-notes.chart" . }}
{{ include "cloud-notes.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "cloud-notes.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cloud-notes.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "cloud-notes.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "cloud-notes.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Render the indexer runtime configuration shared by the Secret and Pod checksums.
*/}}
{{- define "cloud-notes.indexerConfig" -}}
{{- dict "indexer" (dict "enabled" .Values.global.indexer.enabled "provider" (dict "type" .Values.global.indexer.provider "endpoint" .Values.global.indexer.endpoint "apiKey" .Values.global.indexer.apiKey "username" .Values.global.indexer.username "password" .Values.global.indexer.password)) | toJson -}}
{{- end }}
