#!/bin/bash
# Заливка с заменой предыдущей версии.
#
# В репозитории намеренно держится РОВНО ОДИН коммит: --amend переписывает
# его, --force-with-lease заменяет на сервере. Прошлые версии файлов в
# интерфейсе GitHub не остаются — ни в списке коммитов, ни в History файла.
#
# Цена: откатиться на прежнюю версию через git нельзя, её больше нет.
#
# Использование:  ./push.sh ["сообщение коммита"]

set -e
cd "$(dirname "$0")"

git add -A
git commit --amend -m "${1:-Enforcer для Android}"
git push --force-with-lease

echo
echo "залито. Коммитов в репозитории: $(git rev-list --count HEAD)"
echo "сборка: https://github.com/alt17/enforcer-android/actions"
