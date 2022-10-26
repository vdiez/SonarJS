import React from 'react';
import defaultParserInterface from './utils/defaultESTreeParserInterface';
import pkg from 'espree/package.json';

const ID = 'sonarjs';

export default {
  ...defaultParserInterface,

  id: ID,
  displayName: ID,
  version: pkg.version,
  homepage: pkg.homepage,
  locationProps: new Set(['range', 'loc', 'start', 'end']),

  loadParser(callback) {
    callback('sonarjs');
  },

  async parse(sonarjs, code, options) {
    await fetch('/init-linter', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        rules: [{
          key: 'no-extra-semi',
          configurations: [],
          fileTypeTarget: ['MAIN']
        }, {
          key: 'unused-import',
          configurations: [],
          fileTypeTarget: ['MAIN']
        }],
        environments: [],
        globals: [],
      })
    });
    const res = await fetch('/analyze-js', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        filePath: 'astexplorer',
        fileContent: code,
        fileType: 'MAIN',
        tsConfigs: []
      }
)    });

    let pos = 1;
    const lines = [pos];
    for (const line of code.split('\n')) {
      pos += line.length + 1;
      lines.push(pos);
    }
    let json = await res.json();
    for (const issue of json.issues) {
      issue.type = issue.ruleId;
      issue.start = lines[issue.line - 1] + issue.column - 1;
      issue.end = lines[issue.endLine - 1] + issue.endColumn - 1;
    }
    return {type: 'Issues', issues: json.issues, ast: json.ast};
  },

  nodeToRange(node) {
    return typeof node.start === 'number' ? [node.start, node.end] : undefined;
  },

  getDefaultOptions() {
    return {
      range: true,
      loc: false,
      comment: false,
      attachComment: false,
      tokens: false,
      tolerant: false,
      ecmaVersion: 10,
      sourceType: 'module',

      ecmaFeatures: {
        jsx: true,
        globalReturn: true,
        impliedStrict: false,
      },
    };
  },

  _getSettingsConfiguration() {
    const defaultOptions = this.getDefaultOptions();

    return {
      fields: [
        ['ecmaVersion', [3, 5, 6, 7, 8, 9, 10, 11], value => Number(value)],
        ['sourceType', ['script', 'module']],
        'range',
        'loc',
        'comment',
        'attachComment',
        'tokens',
        'tolerant',
        {
          key: 'ecmaFeatures',
          title: 'ecmaFeatures',
          fields: Object.keys(defaultOptions.ecmaFeatures),
          settings:
          settings => settings.ecmaFeatures || {...defaultOptions.ecmaFeatures},
        },
      ],
    };
  },

  renderSettings(parserSettings, onChange) {
    return (
      <div>
        <p>
          <a
            href="https://github.com/eslint/espree#usage"
            target="_blank" rel="noopener noreferrer">
            Option descriptions
          </a>
        </p>
        {defaultParserInterface.renderSettings.call(
          this,
          parserSettings,
          onChange,
        )}
      </div>
    );
  },
};
